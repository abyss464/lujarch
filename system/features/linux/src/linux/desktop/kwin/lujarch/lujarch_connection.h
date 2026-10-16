/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later

    The producer side of the Lujarch display protocol (lujarch_display.h): one stream connection to
    the host's socket (lujarch_linux), binary messages with their fds as SCM_RIGHTS. The Terminal
    app is the peer; the host relays. This class only frames and parses; the backend decides what
    to do.
*/
#pragma once

#include "lujarch_display.h"
#include "utils/filedescriptor.h"

#include <QByteArray>
#include <QList>
#include <QObject>
#include <QRect>
#include <QSize>

#include <deque>
#include <memory>
#include <vector>

class QSocketNotifier;
class QTimer;

namespace KWin
{

struct LujarchPool
{
    struct Buffer
    {
        FileDescriptor fd;
        uint32_t stride = 0;
        uint32_t offset = 0;
        uint64_t size = 0;
    };
    uint32_t id = 0;
    QSize size;
    uint32_t format = 0;
    uint64_t modifier = 0;
    std::vector<Buffer> buffers;
};

class LujarchConnection : public QObject
{
    Q_OBJECT

public:
    explicit LujarchConnection(const QString &socketPath, QObject *parent = nullptr);
    ~LujarchConnection() override;

    /** Connects now and keeps retrying every second while the host is not there. */
    void start();
    bool isConnected() const;

    /** LJD_PRESENT; @p fence is the acquire sync_file or invalid (the rendering is finished). */
    bool present(uint32_t poolId, uint32_t index, uint32_t seq, FileDescriptor fence, const QList<QRect> &damage);
    bool requestFrame();

Q_SIGNALS:
    void hostConnected();
    void hostDisconnected();
    void peerChanged(bool connected);
    void configured(const ljd_configure &configure);
    void poolReceived(const std::shared_ptr<LujarchPool> &pool);
    void released(uint32_t poolId, uint32_t index, FileDescriptor &fence);
    void frame(const ljd_frame &frame);
    void presented(const ljd_presented &presented);
    void touch(int64_t timeNs, const QList<ljd_touch_point> &points);
    void key(const ljd_key &key);
    void pointer(const ljd_pointer &pointer);

private:
    void tryConnect();
    void onReadable();
    bool handleMessage(const ljd_header &header, const char *payload, std::vector<FileDescriptor> &fds);
    bool sendMessage(uint16_t type, const void *payload, uint32_t size, int fd);
    void drop(const char *why);

    const QString m_socketPath;
    FileDescriptor m_fd;
    QSocketNotifier *m_notifier = nullptr;
    QTimer *m_retryTimer = nullptr;
    QByteArray m_in;
    std::deque<FileDescriptor> m_fdQueue;
    bool m_loggedRetry = false;
};

} // namespace KWin
