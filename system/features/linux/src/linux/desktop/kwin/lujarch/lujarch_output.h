/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later

    The one output of the Lujarch backend: the Terminal app's desktop view. Its mode is the size of
    the app's buffers, its scale Android's density / 160, its refresh rate the display's. A frame
    is complete when the app reports the vsync in which it applied the present (LJD_FRAME).
*/
#pragma once

#include "core/backendoutput.h"

#include <QObject>
#include <chrono>
#include <memory>

class QTimer;

namespace KWin
{

class LujarchBackend;
class LujarchEglLayer;
class OutputFrame;
class RenderLoop;

class LujarchOutput : public BackendOutput
{
    Q_OBJECT

public:
    LujarchOutput(LujarchBackend *backend);
    ~LujarchOutput() override;

    std::expected<void, OutputError> testPresentation(const std::shared_ptr<OutputFrame> &frame) override;
    std::expected<void, OutputError> present(const QList<OutputLayer *> &layersToUpdate, const std::shared_ptr<OutputFrame> &frame) override;
    void applyChanges(const OutputConfiguration &config) override;

    /** Size in pixels, scale, refresh in mHz; returns whether anything changed. */
    bool setHostMode(const QSize &size, qreal scale, int refreshMilliHz);

    /** The app applied present @p seq at @p vsync (LJD_FRAME) or dropped it (LJD_PRESENTED). */
    void frameApplied(uint32_t seq, std::chrono::nanoseconds timestamp);

    /** Rendering stops while the view is off screen or no pool is there; resumes with a full repaint. */
    void setRenderingAllowed(bool allowed);
    /** No buffer is free: hold the render loop until a release comes back. */
    void setStarved(bool starved);

    void setEglLayer(std::unique_ptr<LujarchEglLayer> &&layer);
    LujarchEglLayer *eglLayer() const;

private:
    void completeFrame(std::chrono::nanoseconds timestamp);
    void dropFrame();
    void updateInhibit();

    LujarchBackend *const m_backend;
    std::unique_ptr<LujarchEglLayer> m_layer;
    std::shared_ptr<OutputFrame> m_frame;
    uint32_t m_frameSeq = 0;
    QTimer *m_frameTimeout;
    qreal m_hostScale = 1;
    bool m_renderingAllowed = false;
    bool m_starved = false;
    bool m_inhibited = false;
};

} // namespace KWin
