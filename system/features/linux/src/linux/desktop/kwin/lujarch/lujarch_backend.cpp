/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include <algorithm>
#include "lujarch_backend.h"
#include "lujarch_connection.h"
#include "lujarch_egl_backend.h"
#include "lujarch_input.h"
#include "lujarch_logging.h"
#include "lujarch_output.h"

#include "core/renderdevice.h"

#include <KConfigGroup>
#include <KSharedConfig>

#include <QProcess>
#include <QTimer>

#include <chrono>
#include <linux/input-event-codes.h>

namespace KWin
{

// Before the app's first configure: the phone's panel in portrait at Android's default density.
static const QSize s_initialSize(1080, 2400);
static constexpr int s_initialDensity = 440;
// The least logical width of the short side; see onConfigured. 720: desktop apps (Dolphin, System
// Settings, Electron apps) fit their minimum widths; on this phone's 1080 pixels that is scale 1.5.
static constexpr int s_minLogicalShortSide = 720;
static constexpr int s_initialRefresh = 120000;
// How long the mouse's pointer device outlives a leave. Android ends hovering with an exit right
// before a button press, and a pointer crossing the view's edge comes back at once; removing the
// device at each would drop KWin's pointer capability and lose the click while clients bind a new
// wl_pointer, and flip tablet mode.
static constexpr int s_pointerLeaveGraceMs = 500;

// ljd_pointer.buttons bits and their evdev buttons.
static constexpr struct
{
    uint32_t bit;
    quint32 button;
} s_pointerButtons[] = {
    {1, BTN_LEFT},
    {2, BTN_RIGHT},
    {4, BTN_MIDDLE},
    {8, BTN_SIDE},
    {16, BTN_EXTRA},
};
static constexpr uint32_t s_pointerButtonMask = 1 | 2 | 4 | 8 | 16;

static std::chrono::microseconds toMicroseconds(int64_t ns)
{
    if (ns <= 0) {
        return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch());
    }
    return std::chrono::microseconds(ns / 1000);
}

LujarchBackend::LujarchBackend(const QString &socketPath, QObject *parent)
    : OutputBackend(parent)
    , m_socketPath(socketPath.isEmpty() ? QStringLiteral(LJD_SOCKET_PATH) : socketPath)
{
}

LujarchBackend::~LujarchBackend()
{
    m_connection.reset();
    if (m_output) {
        m_output->setEglLayer(nullptr);
        m_output->unref();
        m_output = nullptr;
    }
}

bool LujarchBackend::initialize()
{
    m_touchDevice = std::make_unique<LujarchInputDevice>(LujarchInputDevice::Kind::Touch);
    m_keyboardDevice = std::make_unique<LujarchInputDevice>(LujarchInputDevice::Kind::Keyboard);
    m_pointerRemoveTimer = new QTimer(this);
    m_pointerRemoveTimer->setSingleShot(true);
    m_pointerRemoveTimer->setInterval(s_pointerLeaveGraceMs);
    connect(m_pointerRemoveTimer, &QTimer::timeout, this, &LujarchBackend::removePointer);

    m_output = new LujarchOutput(this);
    m_output->setHostMode(s_initialSize, s_initialDensity / 160.0, s_initialRefresh);
    Q_EMIT outputAdded(m_output);
    Q_EMIT outputsQueried();

    m_connection = std::make_unique<LujarchConnection>(m_socketPath);
    connect(m_connection.get(), &LujarchConnection::hostDisconnected, this, [this]() {
        onPeerChanged(false);
    });
    connect(m_connection.get(), &LujarchConnection::peerChanged, this, &LujarchBackend::onPeerChanged);
    connect(m_connection.get(), &LujarchConnection::configured, this, &LujarchBackend::onConfigured);
    connect(m_connection.get(), &LujarchConnection::poolReceived, this, &LujarchBackend::onPool);
    connect(m_connection.get(), &LujarchConnection::released, this, &LujarchBackend::onReleased);
    connect(m_connection.get(), &LujarchConnection::frame, this, &LujarchBackend::onFrame);
    connect(m_connection.get(), &LujarchConnection::presented, this, &LujarchBackend::onPresented);
    connect(m_connection.get(), &LujarchConnection::touch, this, &LujarchBackend::onTouch);
    connect(m_connection.get(), &LujarchConnection::key, this, &LujarchBackend::onKey);
    connect(m_connection.get(), &LujarchConnection::pointer, this, &LujarchBackend::onPointer);
    m_connection->start();
    return true;
}

std::unique_ptr<EglBackend> LujarchBackend::createOpenGLBackend(RenderDevice *renderDevice)
{
    // KWin's compositor picks the render device (the session's KWIN_RENDER_NODES): msm_drm's
    // render node, on which Zink takes Turnip on KGSL.
    qCInfo(KWIN_LUJARCH) << "render device" << renderDevice->path();
    return std::make_unique<LujarchEglBackend>(this, renderDevice);
}

std::unique_ptr<InputBackend> LujarchBackend::createInputBackend()
{
    auto inputBackend = std::make_unique<LujarchInputBackend>(this);
    // Kept to add and remove the mouse's device; KWin's input owns it.
    m_inputBackend = inputBackend.get();
    return inputBackend;
}

QList<CompositingType> LujarchBackend::supportedCompositors() const
{
    return {OpenGLCompositing};
}

QList<BackendOutput *> LujarchBackend::outputs() const
{
    if (!m_output) {
        return {};
    }
    return {m_output};
}

LujarchConnection *LujarchBackend::connection() const
{
    return m_connection.get();
}

LujarchInputDevice *LujarchBackend::touchDevice() const
{
    return m_touchDevice.get();
}

LujarchInputDevice *LujarchBackend::keyboardDevice() const
{
    return m_keyboardDevice.get();
}

LujarchInputDevice *LujarchBackend::pointerDevice() const
{
    return m_pointerDevice.get();
}

void LujarchBackend::layerAttached(LujarchOutput *output)
{
    if (m_pendingPool) {
        auto pool = std::move(m_pendingPool);
        onPool(pool);
    }
    Q_UNUSED(output)
    updateRendering();
}

void LujarchBackend::updateRendering()
{
    if (!m_output) {
        return;
    }
    LujarchEglLayer *layer = m_output->eglLayer();
    const bool allowed = m_peer && m_visible && layer && layer->hasPool();
    m_output->setRenderingAllowed(allowed);
}

void LujarchBackend::dropPool()
{
    m_pendingPool.reset();
    if (m_output && m_output->eglLayer()) {
        m_output->eglLayer()->releaseBuffers();
    }
}

void LujarchBackend::onPeerChanged(bool connected)
{
    if (m_peer == connected) {
        return;
    }
    m_peer = connected;
    if (!connected) {
        // The app took every buffer back; a touch sequence in progress ends here.
        if (!m_touchIds.isEmpty()) {
            m_touchDevice->touchCancel();
            m_touchIds.clear();
        }
        removePointer();
        m_visible = false;
        dropPool();
    }
    updateRendering();
}

void LujarchBackend::onConfigured(const ljd_configure &configure)
{
    const QSize size(configure.width, configure.height);
    // Android's density, but never so large that the short side has fewer than s_minLogicalShortSide
    // logical pixels: desktop apps have minimum sizes wider than a phone's 411 dp and would run off
    // the screen.
    qreal scale = configure.density_dpi > 0 ? configure.density_dpi / 160.0 : m_output->scale();
    if (size.isValid()) {
        scale = std::min(scale, std::min(size.width(), size.height()) / qreal(s_minLogicalShortSide));
    }
    qCInfo(KWIN_LUJARCH) << "configure" << size << "rotation" << configure.rotation * 90 << "density" << configure.density_dpi
                         << "refresh" << configure.refresh_mhz << "visible" << configure.visible;
    if (size.isValid() && m_output->setHostMode(size, scale, configure.refresh_mhz)) {
        // The backend changed the mode itself, so the workspace has to lay the output out again.
        Q_EMIT outputsQueried();
        updateDockedMode(size);
    }
    m_visible = configure.visible != 0;
    updateRendering();
}

void LujarchBackend::onPool(const std::shared_ptr<LujarchPool> &pool)
{
    LujarchEglLayer *layer = m_output ? m_output->eglLayer() : nullptr;
    if (!layer) {
        m_pendingPool = pool;
        return;
    }
    if (pool->size != m_output->modeSize()) {
        // The buffers decide: a pool for a size the configure has not announced yet still renders.
        m_output->setHostMode(pool->size, m_output->scale(), m_output->refreshRate());
        Q_EMIT outputsQueried();
        updateDockedMode(pool->size);
    }
    if (!layer->importPool(pool)) {
        qCWarning(KWIN_LUJARCH) << "pool" << pool->id << "could not be imported; nothing is rendered until the next pool";
        layer->releaseBuffers();
    }
    m_output->setStarved(false);
    updateRendering();
}

void LujarchBackend::onReleased(uint32_t poolId, uint32_t index, FileDescriptor &fence)
{
    LujarchEglLayer *layer = m_output ? m_output->eglLayer() : nullptr;
    if (!layer) {
        return;
    }
    layer->bufferReleased(poolId, index, std::move(fence));
    if (layer->freeBufferCount() > 0) {
        m_output->setStarved(false);
    }
}

void LujarchBackend::onFrame(const ljd_frame &frame)
{
    if (frame.seq != 0) {
        m_output->frameApplied(frame.seq, std::chrono::nanoseconds(frame.vsync_ns));
    }
}

void LujarchBackend::onPresented(const ljd_presented &presented)
{
    if (presented.flags & LJD_F_DISCARDED) {
        m_output->frameApplied(presented.seq, std::chrono::steady_clock::now().time_since_epoch());
    }
}

QPointF LujarchBackend::mapToGlobal(float x, float y) const
{
    // Buffers are upright and the output transform is Normal: buffer pixels / scale are logical.
    return m_output->position() + QPointF(x, y) / m_output->scale();
}

void LujarchBackend::onTouch(int64_t timeNs, const QList<ljd_touch_point> &points)
{
    const auto time = toMicroseconds(timeNs);
    bool frame = false;
    for (const ljd_touch_point &point : points) {
        switch (point.type) {
        case LJD_TOUCH_DOWN:
            if (!m_touchIds.contains(point.id)) {
                m_touchIds.append(point.id);
                m_touchDevice->touchDown(point.id, mapToGlobal(point.x, point.y), time);
                frame = true;
            }
            break;
        case LJD_TOUCH_MOTION:
            if (m_touchIds.contains(point.id)) {
                m_touchDevice->touchMotion(point.id, mapToGlobal(point.x, point.y), time);
                frame = true;
            }
            break;
        case LJD_TOUCH_UP:
            if (m_touchIds.removeOne(point.id)) {
                m_touchDevice->touchUp(point.id, time);
                frame = true;
            }
            break;
        case LJD_TOUCH_CANCEL:
            if (!m_touchIds.isEmpty()) {
                m_touchDevice->touchCancel();
                m_touchIds.clear();
            }
            frame = false;
            break;
        default:
            break;
        }
    }
    if (frame) {
        m_touchDevice->touchFrame();
    }
}

void LujarchBackend::onKey(const ljd_key &key)
{
    m_keyboardDevice->key(key.keycode, key.pressed != 0, toMicroseconds(key.time_ns));
}

void LujarchBackend::onPointer(const ljd_pointer &pointer)
{
    const auto time = toMicroseconds(pointer.time_ns);
    if (pointer.flags & LJD_POINTER_LEAVE) {
        if (m_pointerDevice) {
            // A button held across the leave would stay pressed in KWin and in the client.
            releasePointerButtons(time);
            m_pointerRemoveTimer->start();
        }
        return;
    }
    m_pointerRemoveTimer->stop();
    bool added = false;
    if (!m_pointerDevice) {
        m_pointerDevice = std::make_unique<LujarchInputDevice>(LujarchInputDevice::Kind::Pointer);
        if (m_inputBackend) {
            m_inputBackend->addDevice(m_pointerDevice.get());
        }
        m_pointerButtons = 0;
        added = true;
        qCInfo(KWIN_LUJARCH) << "mouse in use: pointer device added";
    }

    // One frame per event, as KWin's libinput backend groups them: the motion, each button, the
    // wheel.
    const QPointF position = mapToGlobal(pointer.x, pointer.y);
    if (added || position != m_pointerPosition) {
        m_pointerPosition = position;
        m_pointerDevice->pointerMotionAbsolute(position, time);
        m_pointerDevice->pointerFrame();
    }

    const uint32_t buttons = pointer.buttons & s_pointerButtonMask;
    const uint32_t changed = buttons ^ m_pointerButtons;
    m_pointerButtons = buttons;
    for (const auto &entry : s_pointerButtons) {
        if (changed & entry.bit) {
            m_pointerDevice->pointerButton(entry.button, buttons & entry.bit, time);
            m_pointerDevice->pointerFrame();
        }
    }

    if (pointer.scroll_v120 != 0 || pointer.scroll_h120 != 0) {
        if (pointer.scroll_v120 != 0) {
            m_pointerDevice->pointerAxis(PointerAxis::Vertical, pointer.scroll_v120, time);
        }
        if (pointer.scroll_h120 != 0) {
            m_pointerDevice->pointerAxis(PointerAxis::Horizontal, pointer.scroll_h120, time);
        }
        m_pointerDevice->pointerFrame();
    }
}

void LujarchBackend::releasePointerButtons(std::chrono::microseconds time)
{
    if (!m_pointerDevice) {
        m_pointerButtons = 0;
        return;
    }
    for (const auto &entry : s_pointerButtons) {
        if (m_pointerButtons & entry.bit) {
            m_pointerDevice->pointerButton(entry.button, false, time);
            m_pointerDevice->pointerFrame();
        }
    }
    m_pointerButtons = 0;
}

void LujarchBackend::removePointer()
{
    m_pointerRemoveTimer->stop();
    if (!m_pointerDevice) {
        return;
    }
    releasePointerButtons(toMicroseconds(0));
    if (m_inputBackend) {
        m_inputBackend->removeDevice(m_pointerDevice.get());
    }
    // Deleted later, as KWin's libinput backend does with a removed device: the input code may
    // still hold it in the event being processed.
    m_pointerDevice.release()->deleteLater();
    qCInfo(KWIN_LUJARCH) << "mouse gone: pointer device removed";
}

void LujarchBackend::updateDockedMode(const QSize &size)
{
    // Plasma Mobile turns Docked Mode (its convergence mode) on by itself only when a second
    // screen appears. With KWIN_LUJARCH_DOCKED_FOLLOWS_ORIENTATION=1 in the session, Docked Mode
    // follows the orientation. It is set on each change of orientation only: the quick setting
    // still switches it within one orientation.
    if (qEnvironmentVariableIntValue("KWIN_LUJARCH_DOCKED_FOLLOWS_ORIENTATION") != 1) {
        return;
    }
    const bool landscape = size.width() > size.height();
    if (m_landscape == landscape) {
        return;
    }
    m_landscape = landscape;
    auto config = KSharedConfig::openConfig(QStringLiteral("plasmamobilerc"));
    KConfigGroup group(config, QStringLiteral("General"));
    if (group.readEntry("convergenceModeEnabled", false) != landscape) {
        // What Plasma Mobile's own Docked Mode toggle does (MobileShellSettings).
        group.writeEntry("convergenceModeEnabled", landscape, KConfigGroup::Notify);
        config->sync();
        QProcess::startDetached(QStringLiteral("plasma-mobile-envmanager"), {QStringLiteral("--apply-settings")});
        qCInfo(KWIN_LUJARCH) << "Docked Mode" << (landscape ? "on (landscape)" : "off (portrait)");
    }
}

} // namespace KWin

#include "moc_lujarch_backend.cpp"
