/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "lujarch_egl_backend.h"
#include "lujarch_backend.h"
#include "lujarch_connection.h"
#include "lujarch_logging.h"
#include "lujarch_output.h"

#include "core/graphicsbuffer.h"
#include "core/renderdevice.h"
#include "opengl/eglcontext.h"
#include "opengl/egldisplay.h"
#include "opengl/eglnativefence.h"
#include "opengl/glframebuffer.h"
#include "opengl/glutils.h"

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

namespace KWin
{

LujarchEglLayer::LujarchEglLayer(LujarchOutput *output, LujarchEglBackend *backend)
    : OutputLayer(output, OutputLayerType::Primary)
    , m_backend(backend)
    , m_output(output)
{
}

LujarchEglLayer::~LujarchEglLayer()
{
    releaseBuffers();
}

void LujarchEglLayer::releaseBuffers()
{
    if (auto *context = m_backend->openglContext()) {
        context->makeCurrent();
    }
    m_slots.clear();
    m_current = -1;
    m_rendered = false;
    m_acquireFence = FileDescriptor();
}

bool LujarchEglLayer::hasPool() const
{
    return !m_slots.empty();
}

int LujarchEglLayer::freeBufferCount() const
{
    return std::ranges::count_if(m_slots, [](const Slot &slot) {
        return slot.free;
    });
}

bool LujarchEglLayer::importPool(const std::shared_ptr<LujarchPool> &pool)
{
    releaseBuffers();
    auto *context = m_backend->openglContext();
    if (!context || !context->makeCurrent()) {
        qCWarning(KWIN_LUJARCH) << "no current EGL context for pool" << pool->id;
        return false;
    }

    std::vector<Slot> slots;
    for (size_t i = 0; i < pool->buffers.size(); i++) {
        const LujarchPool::Buffer &buffer = pool->buffers[i];
        DmaBufAttributes attributes;
        attributes.planeCount = 1;
        attributes.width = pool->size.width();
        attributes.height = pool->size.height();
        attributes.format = pool->format;
        attributes.modifier = pool->modifier;
        attributes.fd[0] = buffer.fd.duplicate();
        attributes.offset[0] = buffer.offset;
        attributes.pitch[0] = buffer.stride;
        if (!attributes.fd[0].isValid()) {
            qCWarning(KWIN_LUJARCH) << "pool" << pool->id << "buffer" << i << ": cannot duplicate the dma-buf";
            return false;
        }

        std::shared_ptr<GLTexture> texture = m_backend->openglContext()->importDmaBufAsTexture(attributes);
        if (!texture) {
            qCWarning(KWIN_LUJARCH) << "pool" << pool->id << "buffer" << i << ": EGL import failed (format"
                                    << Qt::hex << pool->format << "modifier" << pool->modifier << Qt::dec
                                    << "stride" << buffer.stride << ")";
            return false;
        }
        // Rendered top-down for SurfaceFlinger, as the DRM backend does for scanout.
        texture->setContentTransform(OutputTransform::FlipY);
        auto framebuffer = std::make_unique<GLFramebuffer>(texture.get());
        if (!framebuffer->valid()) {
            qCWarning(KWIN_LUJARCH) << "pool" << pool->id << "buffer" << i << ": not renderable (external-only modifier?)";
            return false;
        }
        slots.push_back(Slot{
            .texture = std::move(texture),
            .framebuffer = std::move(framebuffer),
            .releaseFence = FileDescriptor(),
            .damage = Region::infinite(),
            .free = true,
            .lastUsed = 0,
        });
    }
    m_slots = std::move(slots);
    m_poolId = pool->id;
    qCInfo(KWIN_LUJARCH) << "pool" << pool->id << ":" << m_slots.size() << "buffers" << pool->size
                         << "format" << Qt::hex << pool->format << "modifier" << pool->modifier << Qt::dec << "imported";
    addDeviceRepaint(Region::infinite());
    return true;
}

void LujarchEglLayer::bufferReleased(uint32_t poolId, uint32_t index, FileDescriptor &&fence)
{
    if (poolId != m_poolId || index >= m_slots.size()) {
        return; // a buffer of a pool that was replaced
    }
    Slot &slot = m_slots[index];
    slot.free = true;
    slot.releaseFence = std::move(fence);
}

void LujarchEglLayer::waitReleaseFence(Slot &slot)
{
    if (!slot.releaseFence.isValid()) {
        return;
    }
    // GPU-side wait: the composite waits for SurfaceFlinger to be done with the buffer without
    // blocking KWin's thread. Zink imports the sync_file as a temporary binary semaphore.
    EglDisplay *display = m_backend->eglDisplayObject();
    if (display->supportsNativeFence()) {
        // EGL takes the fd only when the import succeeds; the original stays for the CPU path.
        FileDescriptor imported = slot.releaseFence.duplicate();
        EGLNativeFence fence = EGLNativeFence::importFence(display, std::move(imported));
        if (!imported.isValid() && fence.waitSync()) {
            slot.releaseFence = FileDescriptor();
            return;
        }
    }
    {
        // No EGL fence import: wait on the CPU. The release fence normally signals long before
        // the buffer comes round again (three buffers), so this rarely blocks.
        pollfd pfd{slot.releaseFence.get(), POLLIN, 0};
        if (poll(&pfd, 1, 100) == 0) {
            qCWarning(KWIN_LUJARCH) << "release fence not signalled after 100 ms; rendering anyway";
        }
        slot.releaseFence = FileDescriptor();
    }
}

std::optional<OutputLayerBeginFrameInfo> LujarchEglLayer::beginFrame(OutputFrame *frame)
{
    Q_UNUSED(frame)
    auto *context = m_backend->openglContext();
    if (m_slots.empty() || !context || !context->makeCurrent()) {
        return std::nullopt;
    }
    // The free buffer used longest ago: with three buffers that is the one SurfaceFlinger let go
    // of first, whose release fence is most likely signalled.
    int best = -1;
    for (int i = 0; i < int(m_slots.size()); i++) {
        if (m_slots[i].free && (best < 0 || m_slots[i].lastUsed < m_slots[best].lastUsed)) {
            best = i;
        }
    }
    if (best < 0) {
        return std::nullopt;
    }
    m_current = best;
    m_rendered = false;
    Slot &slot = m_slots[best];
    waitReleaseFence(slot);
    return OutputLayerBeginFrameInfo{
        .renderTarget = RenderTarget(slot.framebuffer.get()),
        .repaint = slot.damage,
    };
}

bool LujarchEglLayer::endFrame(const Region &renderedDeviceRegion, const Region &damagedDeviceRegion, OutputFrame *frame)
{
    Q_UNUSED(renderedDeviceRegion)
    Q_UNUSED(frame)
    if (m_current < 0 || m_current >= int(m_slots.size())) {
        return false;
    }
    for (Slot &slot : m_slots) {
        slot.damage += damagedDeviceRegion;
    }
    m_slots[m_current].damage = Region();

    // The acquire fence: a sync_file that signals when this composite is done. Without native
    // fence support the composite is finished here and the present carries no fence.
    EGLNativeFence fence(m_backend->eglDisplayObject());
    if (fence.isValid()) {
        m_acquireFence = fence.takeFileDescriptor();
    } else {
        glFinish();
        m_acquireFence = FileDescriptor();
    }
    m_presentDamage.clear();
    if (!damagedDeviceRegion.isEmpty() && damagedDeviceRegion != Region::infinite()) {
        for (const Rect &rect : damagedDeviceRegion.rects()) {
            m_presentDamage.append(QRect(rect.x(), rect.y(), rect.width(), rect.height()));
        }
    }
    m_rendered = true;
    return true;
}

std::optional<uint32_t> LujarchEglLayer::presentCurrent()
{
    if (!m_rendered || m_current < 0 || m_current >= int(m_slots.size())) {
        return std::nullopt;
    }
    m_rendered = false;
    Slot &slot = m_slots[m_current];
    LujarchConnection *connection = m_backend->backend()->connection();
    const uint32_t seq = ++m_seq;
    if (!connection->present(m_poolId, m_current, seq, std::move(m_acquireFence), m_presentDamage)) {
        return std::nullopt;
    }
    slot.free = false;
    slot.lastUsed = ++m_useCounter;
    m_current = -1;
    return seq;
}

FormatModifierMap LujarchEglLayer::supportedDrmFormats() const
{
    return m_backend->supportedFormats();
}

LujarchEglBackend::LujarchEglBackend(LujarchBackend *backend, RenderDevice *renderDevice)
    : EglBackend(renderDevice)
    , m_backend(backend)
{
}

LujarchEglBackend::~LujarchEglBackend()
{
    for (BackendOutput *output : m_backend->outputs()) {
        static_cast<LujarchOutput *>(output)->setEglLayer(nullptr);
    }
    cleanup();
}

bool LujarchEglBackend::init()
{
    if (!initClientExtensions()) {
        qCWarning(KWIN_LUJARCH) << "EGL client extensions missing";
        return false;
    }
    if (!createContext()) {
        qCWarning(KWIN_LUJARCH) << "could not create the EGL context";
        return false;
    }
    if (!eglDisplayObject()->supportsNativeFence()) {
        qCWarning(KWIN_LUJARCH) << "EGL_ANDROID_native_fence_sync is missing: every frame is finished on the CPU (glFinish) and presented without a fence";
    }
    initWayland();

    for (BackendOutput *output : m_backend->outputs()) {
        addOutput(output);
    }
    connect(m_backend, &LujarchBackend::outputAdded, this, &LujarchEglBackend::addOutput);
    return true;
}

void LujarchEglBackend::addOutput(BackendOutput *output)
{
    openglContext()->makeCurrent();
    auto *lujarchOutput = static_cast<LujarchOutput *>(output);
    lujarchOutput->setEglLayer(std::make_unique<LujarchEglLayer>(lujarchOutput, this));
    m_backend->layerAttached(lujarchOutput);
}

QList<OutputLayer *> LujarchEglBackend::compatibleOutputLayers(BackendOutput *output)
{
    return {static_cast<LujarchOutput *>(output)->eglLayer()};
}

} // namespace KWin

#include "moc_lujarch_egl_backend.cpp"
