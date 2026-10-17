/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: anland contributors (https://github.com/SuperTurtleDev/anland, branch legacy)
    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "lujarch_input.h"
#include "lujarch_backend.h"

namespace KWin
{

LujarchInputDevice::LujarchInputDevice(Kind kind, QObject *parent)
    : InputDevice(parent)
    , m_kind(kind)
{
    // kwin.patch: TabletModeManager counts a non-libinput device only when it says so. A phone's
    // built-in touchscreen puts the session in tablet mode, as libinput's would; a mouse in use
    // takes it out, as a libinput mouse does (KWin: tablet mode is a touchscreen and no pointer).
    // The pointer device exists only while the mouse is in use, so tablet mode comes back after
    // it leaves.
    setProperty("kwinBlocksTabletMode", kind == Kind::Touch || kind == Kind::Pointer);
}

QString LujarchInputDevice::name() const
{
    switch (m_kind) {
    case Kind::Touch:
        return QStringLiteral("Lujarch touchscreen");
    case Kind::Keyboard:
        return QStringLiteral("Lujarch keyboard");
    case Kind::Pointer:
        return QStringLiteral("Lujarch mouse");
    }
    return QString();
}

bool LujarchInputDevice::isEnabled() const
{
    return m_enabled;
}

void LujarchInputDevice::setEnabled(bool enabled)
{
    m_enabled = enabled;
}

bool LujarchInputDevice::isKeyboard() const
{
    return m_kind == Kind::Keyboard;
}

bool LujarchInputDevice::isPointer() const
{
    return m_kind == Kind::Pointer;
}

bool LujarchInputDevice::isTouchpad() const
{
    return false;
}

bool LujarchInputDevice::isTouch() const
{
    return m_kind == Kind::Touch;
}

bool LujarchInputDevice::isTabletTool() const
{
    return false;
}

bool LujarchInputDevice::isTabletPad() const
{
    return false;
}

bool LujarchInputDevice::isTabletModeSwitch() const
{
    return false;
}

bool LujarchInputDevice::isLidSwitch() const
{
    return false;
}

void LujarchInputDevice::touchDown(qint32 id, const QPointF &position, std::chrono::microseconds time)
{
    Q_EMIT InputDevice::touchDown(id, position, time, this);
}

void LujarchInputDevice::touchMotion(qint32 id, const QPointF &position, std::chrono::microseconds time)
{
    Q_EMIT InputDevice::touchMotion(id, position, time, this);
}

void LujarchInputDevice::touchUp(qint32 id, std::chrono::microseconds time)
{
    Q_EMIT InputDevice::touchUp(id, time, this);
}

void LujarchInputDevice::touchFrame()
{
    Q_EMIT InputDevice::touchFrame(this);
}

void LujarchInputDevice::touchCancel()
{
    Q_EMIT touchCanceled(this);
}

void LujarchInputDevice::key(quint32 keycode, bool pressed, std::chrono::microseconds time)
{
    Q_EMIT keyChanged(keycode, pressed ? KeyboardKeyState::Pressed : KeyboardKeyState::Released, time, this);
}

void LujarchInputDevice::pointerMotionAbsolute(const QPointF &position, std::chrono::microseconds time)
{
    Q_EMIT InputDevice::pointerMotionAbsolute(position, time, this);
}

void LujarchInputDevice::pointerButton(quint32 button, bool pressed, std::chrono::microseconds time)
{
    Q_EMIT pointerButtonChanged(button, pressed ? PointerButtonState::Pressed : PointerButtonState::Released, time, this);
}

void LujarchInputDevice::pointerAxis(PointerAxis axis, qint32 deltaV120, std::chrono::microseconds time)
{
    // libinput's wheel delta is the wheel's angle, 15 degrees per detent (120 in v120), which is
    // what KWin and the clients scale scrolling by; the sign is libinput's too (down, right).
    const qreal delta = deltaV120 * 15.0 / 120.0;
    Q_EMIT pointerAxisChanged(axis, delta, deltaV120, PointerAxisSource::Wheel, false, false, time, this);
}

void LujarchInputDevice::pointerFrame()
{
    Q_EMIT InputDevice::pointerFrame(this);
}

LujarchInputBackend::LujarchInputBackend(LujarchBackend *backend)
    : m_backend(backend)
{
}

void LujarchInputBackend::initialize()
{
    Q_EMIT deviceAdded(m_backend->touchDevice());
    Q_EMIT deviceAdded(m_backend->keyboardDevice());
    // A mouse already in use before KWin's input started (the connection runs from the output
    // backend's start).
    if (LujarchInputDevice *pointer = m_backend->pointerDevice()) {
        Q_EMIT deviceAdded(pointer);
    }
}

void LujarchInputBackend::addDevice(LujarchInputDevice *device)
{
    Q_EMIT deviceAdded(device);
}

void LujarchInputBackend::removeDevice(LujarchInputDevice *device)
{
    Q_EMIT deviceRemoved(device);
}

} // namespace KWin

#include "moc_lujarch_input.cpp"
