/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later

    OpenGL for the Lujarch backend: the app's buffers are imported as EGLImages
    (EGL_EXT_image_dma_buf_import with the modifier the app read from each buffer's handle) and
    KWin composites straight into them. The acquire fence is an EGL native fence (sync_file);
    release fences from the app are waited on by the GPU (eglWaitSync) before a buffer is reused.
*/
#pragma once

#include "core/outputlayer.h"
#include "opengl/eglbackend.h"
#include "utils/filedescriptor.h"

#include <memory>
#include <optional>
#include <vector>

namespace KWin
{
class GLFramebuffer;
class GLTexture;
class LujarchBackend;
class LujarchEglBackend;
class LujarchOutput;
struct LujarchPool;

class LujarchEglLayer : public OutputLayer
{
public:
    LujarchEglLayer(LujarchOutput *output, LujarchEglBackend *backend);
    ~LujarchEglLayer() override;

    std::optional<OutputLayerBeginFrameInfo> doBeginFrame() override;
    bool doEndFrame(const Region &renderedDeviceRegion, const Region &damagedDeviceRegion, OutputFrame *frame) override;

    DrmDevice *scanoutDevice() const override;
    FormatModifierMap supportedDrmFormats() const override;
    void releaseBuffers() override;

    /** Imports a new pool; the old one is dropped (the app takes its buffers back). */
    bool importPool(const std::shared_ptr<LujarchPool> &pool);
    bool hasPool() const;
    /** LJD_RELEASE: the buffer is the producer's again once @p fence signals. */
    void bufferReleased(uint32_t poolId, uint32_t index, FileDescriptor &&fence);
    /** Sends the frame rendered in doBeginFrame/doEndFrame; returns its seq. */
    std::optional<uint32_t> presentCurrent();
    int freeBufferCount() const;

private:
    struct Slot
    {
        std::shared_ptr<GLTexture> texture;
        std::unique_ptr<GLFramebuffer> framebuffer;
        FileDescriptor releaseFence;
        Region damage; // what changed since this buffer was last rendered
        bool free = true;
        uint64_t lastUsed = 0;
    };

    void waitReleaseFence(Slot &slot);

    LujarchEglBackend *const m_backend;
    LujarchOutput *const m_output;
    uint32_t m_poolId = 0;
    std::vector<Slot> m_slots;
    int m_current = -1;
    bool m_rendered = false;
    FileDescriptor m_acquireFence;
    QList<QRect> m_presentDamage;
    uint64_t m_useCounter = 0;
    uint32_t m_seq = 0;
};

class LujarchEglBackend : public EglBackend
{
    Q_OBJECT

public:
    explicit LujarchEglBackend(LujarchBackend *backend);
    ~LujarchEglBackend() override;

    bool init() override;
    QList<OutputLayer *> compatibleOutputLayers(BackendOutput *output) override;
    DrmDevice *drmDevice() const override;

    LujarchBackend *backend() const
    {
        return m_backend;
    }

private:
    void addOutput(BackendOutput *output);

    LujarchBackend *const m_backend;
};

} // namespace KWin
