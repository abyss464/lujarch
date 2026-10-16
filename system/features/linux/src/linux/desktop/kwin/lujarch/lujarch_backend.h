/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later

    The Lujarch output backend: KWin as the desktop compositor of Lujarch's Linux development
    environment, rendering into buffers that the Terminal app (org.lujarch.linux.terminal,
    DesktopActivity) allocates and shows with SurfaceControl. Frames, configure, input and fences
    travel over the display protocol (lujarch_display.h) through lujarch_linux. Structure after
    Anland's KWin backend (its legacy branch).
*/
#pragma once

#include "core/outputbackend.h"
#include "lujarch_display.h"
#include "utils/filedescriptor.h"

#include <QList>
#include <QPointer>
#include <chrono>
#include <memory>
#include <optional>

class QTimer;

namespace KWin
{

class DrmDevice;
class EglDisplay;
class LujarchConnection;
class LujarchInputBackend;
class LujarchInputDevice;
class LujarchOutput;
class RenderDevice;
struct LujarchPool;

class KWIN_EXPORT LujarchBackend : public OutputBackend
{
    Q_OBJECT

public:
    explicit LujarchBackend(const QString &socketPath, QObject *parent = nullptr);
    ~LujarchBackend() override;

    bool initialize() override;
    std::unique_ptr<EglBackend> createOpenGLBackend() override;
    std::unique_ptr<InputBackend> createInputBackend() override;
    QList<CompositingType> supportedCompositors() const override;
    QList<BackendOutput *> outputs() const override;
    EglDisplay *sceneEglDisplayObject() const override;

    DrmDevice *drmDevice() const;
    RenderDevice *renderDevice() const;
    LujarchConnection *connection() const;
    LujarchInputDevice *touchDevice() const;
    LujarchInputDevice *keyboardDevice() const;
    /** The mouse, from the first LJD_POINTER until it leaves; null otherwise. */
    LujarchInputDevice *pointerDevice() const;

    /** The GL backend made the output's layer: import the pool held so far. */
    void layerAttached(LujarchOutput *output);

private:
    void onPeerChanged(bool connected);
    void onConfigured(const ljd_configure &configure);
    void onPool(const std::shared_ptr<LujarchPool> &pool);
    void onReleased(uint32_t poolId, uint32_t index, FileDescriptor &fence);
    void onFrame(const ljd_frame &frame);
    void onPresented(const ljd_presented &presented);
    void onTouch(int64_t timeNs, const QList<ljd_touch_point> &points);
    void onKey(const ljd_key &key);
    void onPointer(const ljd_pointer &pointer);
    void releasePointerButtons(std::chrono::microseconds time);
    void removePointer();
    void dropPool();
    void updateRendering();
    void updateDockedMode(const QSize &size);
    QPointF mapToGlobal(float x, float y) const;

    const QString m_socketPath;
    std::unique_ptr<RenderDevice> m_renderDevice;
    LujarchOutput *m_output = nullptr;
    std::unique_ptr<LujarchConnection> m_connection;
    std::unique_ptr<LujarchInputDevice> m_touchDevice;
    std::unique_ptr<LujarchInputDevice> m_keyboardDevice;
    std::unique_ptr<LujarchInputDevice> m_pointerDevice;
    QPointer<LujarchInputBackend> m_inputBackend;
    QTimer *m_pointerRemoveTimer = nullptr;
    QPointF m_pointerPosition;
    uint32_t m_pointerButtons = 0;
    std::shared_ptr<LujarchPool> m_pendingPool;
    QList<qint32> m_touchIds;
    bool m_peer = false;
    bool m_visible = false;
    std::optional<bool> m_landscape;
};

} // namespace KWin
