/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later

    Input of the Lujarch backend: the phone's touchscreen, hardware keys and a mouse, fed by the
    Terminal app. A touchscreen and a keyboard always, so that KWin and Plasma Mobile see a touch
    device (tablet mode, the on-screen keyboard on touch focus); a pointer only while a mouse is in
    use (from the first LJD_POINTER to a leave), so that no cursor shows and tablet mode holds
    otherwise.
*/
#pragma once

#include "core/inputbackend.h"
#include "core/inputdevice.h"

namespace KWin
{

class LujarchBackend;

class LujarchInputDevice : public InputDevice
{
    Q_OBJECT

public:
    enum class Kind {
        Touch,
        Keyboard,
        Pointer,
    };
    explicit LujarchInputDevice(Kind kind, QObject *parent = nullptr);

    QString name() const override;
    bool isEnabled() const override;
    void setEnabled(bool enabled) override;

    bool isKeyboard() const override;
    bool isPointer() const override;
    bool isTouchpad() const override;
    bool isTouch() const override;
    bool isTabletTool() const override;
    bool isTabletPad() const override;
    bool isTabletModeSwitch() const override;
    bool isLidSwitch() const override;

    void touchDown(qint32 id, const QPointF &position, std::chrono::microseconds time);
    void touchMotion(qint32 id, const QPointF &position, std::chrono::microseconds time);
    void touchUp(qint32 id, std::chrono::microseconds time);
    void touchFrame();
    void touchCancel();
    void key(quint32 keycode, bool pressed, std::chrono::microseconds time);
    void pointerMotionAbsolute(const QPointF &position, std::chrono::microseconds time);
    void pointerButton(quint32 button, bool pressed, std::chrono::microseconds time);
    void pointerAxis(PointerAxis axis, qint32 deltaV120, std::chrono::microseconds time);
    void pointerFrame();

private:
    const Kind m_kind;
    bool m_enabled = true;
};

class LujarchInputBackend : public InputBackend
{
    Q_OBJECT

public:
    explicit LujarchInputBackend(LujarchBackend *backend);

    void initialize() override;

    /** The mouse came or went (LujarchBackend::onPointer). */
    void addDevice(LujarchInputDevice *device);
    void removeDevice(LujarchInputDevice *device);

private:
    LujarchBackend *m_backend;
};

} // namespace KWin
