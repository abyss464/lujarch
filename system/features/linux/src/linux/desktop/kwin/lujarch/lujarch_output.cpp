/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "lujarch_output.h"
#include "lujarch_backend.h"
#include "lujarch_egl_backend.h"
#include "lujarch_logging.h"

#include "core/outputconfiguration.h"
#include "core/renderbackend.h"
#include "core/renderloop.h"

#include <QTimer>

namespace KWin
{

// A present the app never reports (it went away mid-frame, or froze) must not stall KWin's render
// loop forever; after this long the frame counts as shown.
static constexpr int s_frameTimeoutMs = 250;

LujarchOutput::LujarchOutput(LujarchBackend *backend)
    : BackendOutput()
    , m_backend(backend)
    , m_renderLoop(std::make_unique<RenderLoop>(this))
    , m_frameTimeout(new QTimer(this))
{
    setInformation(Information{
        .name = QStringLiteral("Lujarch-1"),
        .manufacturer = QStringLiteral("Lujarch"),
        .model = QStringLiteral("Display view"),
        .internal = true,
    });
    m_frameTimeout->setSingleShot(true);
    m_frameTimeout->setInterval(s_frameTimeoutMs);
    connect(m_frameTimeout, &QTimer::timeout, this, [this]() {
        if (m_frame) {
            qCDebug(KWIN_LUJARCH) << "frame" << m_frameSeq << "not reported by the app; completing it";
            completeFrame(std::chrono::steady_clock::now().time_since_epoch());
        }
    });
    // Rendering starts held: it waits for a pool and a visible configure.
    m_renderLoop->inhibit();
    m_inhibited = true;
}

LujarchOutput::~LujarchOutput()
{
    m_frame.reset();
}

RenderLoop *LujarchOutput::renderLoop() const
{
    return m_renderLoop.get();
}

bool LujarchOutput::testPresentation(const std::shared_ptr<OutputFrame> &frame)
{
    Q_UNUSED(frame)
    return true;
}

bool LujarchOutput::present(const QList<OutputLayer *> &layersToUpdate, const std::shared_ptr<OutputFrame> &frame)
{
    Q_UNUSED(layersToUpdate)
    if (!m_layer) {
        return false;
    }
    const std::optional<uint32_t> seq = m_layer->presentCurrent();
    if (!seq) {
        // Nothing reached the app (no pool any more): the frame is dropped, the loop goes on.
        return true;
    }
    m_frame = frame;
    m_frameSeq = *seq;
    m_frameTimeout->start();
    // Every buffer is with the app: hold the loop until one comes back (LJD_RELEASE).
    setStarved(m_layer->freeBufferCount() == 0);
    return true;
}

void LujarchOutput::applyChanges(const OutputConfiguration &config)
{
    // Mode, scale and transform belong to the app's view: the buffers are upright for the
    // current rotation, and the scale follows Android's density (the owner's display size
    // setting). The rest of the configuration (position, enablement, color) applies as usual.
    BackendOutput::applyChanges(config);
    State next = m_state;
    next.scale = m_hostScale;
    next.scaleSetting = m_hostScale;
    next.transform = OutputTransform::Normal;
    next.manualTransform = OutputTransform::Normal;
    setState(next);
}

bool LujarchOutput::setHostMode(const QSize &size, qreal scale, int refreshMilliHz)
{
    if (refreshMilliHz <= 0) {
        refreshMilliHz = 60000;
    }
    const bool sameMode = m_state.currentMode && m_state.currentMode->size() == size && m_state.currentMode->refreshRate() == uint32_t(refreshMilliHz);
    if (sameMode && qFuzzyCompare(m_hostScale, scale) && m_state.enabled) {
        return false;
    }
    m_hostScale = scale;
    m_renderLoop->setRefreshRate(refreshMilliHz);

    State next = m_state;
    if (!sameMode) {
        auto mode = std::make_shared<OutputMode>(OutputModeline(size, refreshMilliHz, OutputModeline::Flag::Preferred));
        next.modes = {mode};
        next.currentMode = mode;
        next.desiredMode = mode->modeline();
    }
    next.scale = scale;
    next.scaleSetting = scale;
    next.transform = OutputTransform::Normal;
    next.manualTransform = OutputTransform::Normal;
    next.enabled = true;
    setState(next);
    qCInfo(KWIN_LUJARCH) << "mode" << size << "scale" << scale << "refresh" << refreshMilliHz << "mHz";
    return true;
}

void LujarchOutput::completeFrame(std::chrono::nanoseconds timestamp)
{
    m_frameTimeout->stop();
    if (!m_frame) {
        return;
    }
    auto frame = std::move(m_frame);
    frame->presented(timestamp, PresentationMode::VSync);
}

void LujarchOutput::dropFrame()
{
    m_frameTimeout->stop();
    m_frame.reset();
}

void LujarchOutput::frameApplied(uint32_t seq, std::chrono::nanoseconds timestamp)
{
    // seq counts up per connection; the app reports the newest present it applied. A frame older
    // than the pending one was already accounted for.
    if (m_frame && int32_t(seq - m_frameSeq) >= 0) {
        completeFrame(timestamp);
    }
}

void LujarchOutput::updateInhibit()
{
    const bool inhibit = !m_renderingAllowed || m_starved;
    if (inhibit == m_inhibited) {
        return;
    }
    m_inhibited = inhibit;
    if (inhibit) {
        m_renderLoop->inhibit();
    } else {
        m_renderLoop->uninhibit();
        if (m_layer) {
            m_layer->addDeviceRepaint(Region::infinite());
        }
        m_renderLoop->scheduleRepaint();
    }
}

void LujarchOutput::setRenderingAllowed(bool allowed)
{
    if (!allowed) {
        dropFrame();
    }
    m_renderingAllowed = allowed;
    updateInhibit();
}

void LujarchOutput::setStarved(bool starved)
{
    m_starved = starved;
    updateInhibit();
}

void LujarchOutput::setEglLayer(std::unique_ptr<LujarchEglLayer> &&layer)
{
    m_layer = std::move(layer);
}

LujarchEglLayer *LujarchOutput::eglLayer() const
{
    return m_layer.get();
}

} // namespace KWin

#include "moc_lujarch_output.cpp"
