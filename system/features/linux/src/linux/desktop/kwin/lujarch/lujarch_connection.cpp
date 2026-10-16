/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "lujarch_connection.h"
#include "lujarch_logging.h"

#include <QSocketNotifier>
#include <QTimer>

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace KWin
{

static constexpr int s_retryIntervalMs = 1000;
static constexpr size_t s_headerSize = sizeof(ljd_header);

LujarchConnection::LujarchConnection(const QString &socketPath, QObject *parent)
    : QObject(parent)
    , m_socketPath(socketPath)
    , m_retryTimer(new QTimer(this))
{
    m_retryTimer->setInterval(s_retryIntervalMs);
    connect(m_retryTimer, &QTimer::timeout, this, &LujarchConnection::tryConnect);
}

LujarchConnection::~LujarchConnection()
{
    delete m_notifier;
}

void LujarchConnection::start()
{
    tryConnect();
    if (!isConnected()) {
        m_retryTimer->start();
    }
}

bool LujarchConnection::isConnected() const
{
    return m_fd.isValid();
}

// Reads the host's one-line JSON answer to the request. The host answers at once, so a short
// blocking wait is fine here (it runs once per connection, not per frame).
static bool readReplyLine(int fd, QByteArray &line)
{
    line.clear();
    while (line.size() < 4096) {
        pollfd pfd{fd, POLLIN, 0};
        const int ready = poll(&pfd, 1, 3000);
        if (ready <= 0) {
            return false;
        }
        char c;
        const ssize_t n = ::read(fd, &c, 1);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) {
                continue;
            }
            return false;
        }
        if (c == '\n') {
            return true;
        }
        line.append(c);
    }
    return false;
}

void LujarchConnection::tryConnect()
{
    if (isConnected()) {
        m_retryTimer->stop();
        return;
    }
    FileDescriptor fd(::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
    if (!fd.isValid()) {
        qCWarning(KWIN_LUJARCH) << "socket() failed:" << strerror(errno);
        return;
    }
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const QByteArray path = m_socketPath.toLocal8Bit();
    if (size_t(path.size()) >= sizeof(addr.sun_path)) {
        qCWarning(KWIN_LUJARCH) << "socket path too long:" << m_socketPath;
        m_retryTimer->stop();
        return;
    }
    memcpy(addr.sun_path, path.constData(), path.size());
    if (::connect(fd.get(), reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        if (!m_loggedRetry) {
            qCInfo(KWIN_LUJARCH) << "host not reachable at" << m_socketPath << ":" << strerror(errno) << "(retrying every second)";
            m_loggedRetry = true;
        }
        return;
    }
    const char *request = LJD_REQUEST_PRODUCER;
    const size_t requestSize = strlen(request);
    if (::write(fd.get(), request, requestSize) != ssize_t(requestSize)) {
        qCWarning(KWIN_LUJARCH) << "could not send the producer request:" << strerror(errno);
        return;
    }
    QByteArray reply;
    if (!readReplyLine(fd.get(), reply)) {
        qCWarning(KWIN_LUJARCH) << "no answer from the host";
        return;
    }
    if (!reply.contains("\"ok\":true")) {
        if (!m_loggedRetry) {
            qCWarning(KWIN_LUJARCH) << "the host refused the producer:" << reply << "(retrying every second)";
            m_loggedRetry = true;
        }
        return;
    }
    const int flags = fcntl(fd.get(), F_GETFL);
    fcntl(fd.get(), F_SETFL, flags | O_NONBLOCK);

    m_fd = std::move(fd);
    m_loggedRetry = false;
    m_in.clear();
    m_fdQueue.clear();
    m_retryTimer->stop();
    m_notifier = new QSocketNotifier(m_fd.get(), QSocketNotifier::Read);
    connect(m_notifier, &QSocketNotifier::activated, this, &LujarchConnection::onReadable);
    qCInfo(KWIN_LUJARCH) << "connected to the host at" << m_socketPath;
    Q_EMIT hostConnected();
}

void LujarchConnection::drop(const char *why)
{
    if (!isConnected()) {
        return;
    }
    qCWarning(KWIN_LUJARCH) << "host connection closed:" << why;
    if (m_notifier) {
        m_notifier->setEnabled(false);
        m_notifier->deleteLater();
        m_notifier = nullptr;
    }
    m_fd = FileDescriptor();
    m_in.clear();
    m_fdQueue.clear();
    Q_EMIT hostDisconnected();
    m_retryTimer->start();
}

void LujarchConnection::onReadable()
{
    if (!isConnected()) {
        return;
    }
    for (;;) {
        char data[16384];
        // Room for several messages' fds in one read (LJD_MAX_FDS each).
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * LJD_MAX_FDS * 8)];
        iovec iov{data, sizeof(data)};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
        const ssize_t n = recvmsg(m_fd.get(), &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            drop(strerror(errno));
            return;
        }
        for (cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
                const int count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                const int *fds = reinterpret_cast<const int *>(CMSG_DATA(c));
                for (int i = 0; i < count; i++) {
                    m_fdQueue.emplace_back(fds[i]);
                }
            }
        }
        if (msg.msg_flags & MSG_CTRUNC) {
            drop("control data truncated (fds lost)");
            return;
        }
        if (n == 0) {
            drop("end of stream");
            return;
        }
        m_in.append(data, n);
    }

    while (size_t(m_in.size()) >= s_headerSize) {
        ljd_header header;
        memcpy(&header, m_in.constData(), s_headerSize);
        if (header.size > LJD_MAX_PAYLOAD || header.nfds > LJD_MAX_FDS) {
            drop("oversized message");
            return;
        }
        if (size_t(m_in.size()) < s_headerSize + header.size) {
            break;
        }
        std::vector<FileDescriptor> fds;
        for (int i = 0; i < header.nfds && !m_fdQueue.empty(); i++) {
            fds.push_back(std::move(m_fdQueue.front()));
            m_fdQueue.pop_front();
        }
        if (fds.size() != header.nfds) {
            qCWarning(KWIN_LUJARCH) << "message type" << header.type << "announced" << header.nfds << "fds, got" << fds.size();
        }
        const QByteArray payload = m_in.mid(s_headerSize, header.size);
        m_in.remove(0, s_headerSize + header.size);
        if (!handleMessage(header, payload.constData(), fds)) {
            return; // the connection was dropped
        }
        if (!isConnected()) {
            return;
        }
    }
}

template<typename T>
static bool readPayload(const ljd_header &header, const char *payload, T &out)
{
    if (header.size < sizeof(T)) {
        return false;
    }
    memcpy(&out, payload, sizeof(T));
    return true;
}

bool LujarchConnection::handleMessage(const ljd_header &header, const char *payload, std::vector<FileDescriptor> &fds)
{
    switch (header.type) {
    case LJD_PEER: {
        ljd_peer peer;
        if (!readPayload(header, payload, peer)) {
            break;
        }
        qCInfo(KWIN_LUJARCH) << "app" << (peer.connected ? "attached" : "detached");
        Q_EMIT peerChanged(peer.connected != 0);
        return true;
    }
    case LJD_CONFIGURE: {
        ljd_configure configure;
        if (!readPayload(header, payload, configure)) {
            break;
        }
        Q_EMIT configured(configure);
        return true;
    }
    case LJD_POOL: {
        ljd_pool wire;
        if (!readPayload(header, payload, wire)) {
            break;
        }
        if (wire.count == 0 || wire.count > LJD_MAX_BUFFERS || fds.size() != wire.count) {
            qCWarning(KWIN_LUJARCH) << "bad pool" << wire.pool_id << "count" << wire.count << "fds" << fds.size();
            return true;
        }
        auto pool = std::make_shared<LujarchPool>();
        pool->id = wire.pool_id;
        pool->size = QSize(wire.width, wire.height);
        pool->format = wire.format;
        pool->modifier = wire.modifier;
        for (uint32_t i = 0; i < wire.count; i++) {
            pool->buffers.push_back(LujarchPool::Buffer{
                .fd = std::move(fds[i]),
                .stride = wire.buffers[i].stride,
                .offset = wire.buffers[i].offset,
                .size = wire.buffers[i].size,
            });
        }
        Q_EMIT poolReceived(pool);
        return true;
    }
    case LJD_RELEASE: {
        ljd_release release;
        if (!readPayload(header, payload, release)) {
            break;
        }
        FileDescriptor fence;
        if ((release.flags & LJD_F_FENCE) && !fds.empty()) {
            fence = std::move(fds[0]);
        }
        Q_EMIT released(release.pool_id, release.index, fence);
        return true;
    }
    case LJD_FRAME: {
        ljd_frame frameEvent;
        if (!readPayload(header, payload, frameEvent)) {
            break;
        }
        Q_EMIT frame(frameEvent);
        return true;
    }
    case LJD_PRESENTED: {
        ljd_presented presentedEvent;
        if (!readPayload(header, payload, presentedEvent)) {
            break;
        }
        Q_EMIT presented(presentedEvent);
        return true;
    }
    case LJD_TOUCH: {
        ljd_touch touchHeader;
        if (!readPayload(header, payload, touchHeader)) {
            break;
        }
        const uint32_t count = std::min<uint32_t>(touchHeader.count, LJD_MAX_TOUCH);
        if (header.size < sizeof(ljd_touch) + count * sizeof(ljd_touch_point)) {
            break;
        }
        QList<ljd_touch_point> points(count);
        memcpy(points.data(), payload + sizeof(ljd_touch), count * sizeof(ljd_touch_point));
        Q_EMIT touch(touchHeader.time_ns, points);
        return true;
    }
    case LJD_KEY: {
        ljd_key keyEvent;
        if (!readPayload(header, payload, keyEvent)) {
            break;
        }
        Q_EMIT key(keyEvent);
        return true;
    }
    case LJD_POINTER: {
        ljd_pointer pointerEvent;
        if (!readPayload(header, payload, pointerEvent)) {
            break;
        }
        Q_EMIT pointer(pointerEvent);
        return true;
    }
    default:
        // Unknown types are skipped by size; their fds close with `fds`.
        return true;
    }
    qCWarning(KWIN_LUJARCH) << "short payload for message type" << header.type << "size" << header.size;
    return true;
}

bool LujarchConnection::sendMessage(uint16_t type, const void *payload, uint32_t size, int fd)
{
    if (!isConnected()) {
        return false;
    }
    ljd_header header{type, uint16_t(fd >= 0 ? 1 : 0), size};
    QByteArray out(reinterpret_cast<const char *>(&header), s_headerSize);
    out.append(static_cast<const char *>(payload), size);

    size_t sent = 0;
    bool fdSent = false;
    while (sent < size_t(out.size())) {
        iovec iov{out.data() + sent, out.size() - sent};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))];
        if (fd >= 0 && !fdSent) {
            msg.msg_control = control;
            msg.msg_controllen = sizeof(control);
            cmsghdr *c = CMSG_FIRSTHDR(&msg);
            c->cmsg_level = SOL_SOCKET;
            c->cmsg_type = SCM_RIGHTS;
            c->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(c), &fd, sizeof(int));
        }
        const ssize_t n = sendmsg(m_fd.get(), &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // The host reads continuously; a full socket buffer means it is stalled (or frozen
                // with the desktop). Wait briefly rather than splitting the message stream.
                pollfd pfd{m_fd.get(), POLLOUT, 0};
                if (poll(&pfd, 1, 200) <= 0) {
                    drop("send buffer full");
                    return false;
                }
                continue;
            }
            drop(strerror(errno));
            return false;
        }
        fdSent = true;
        sent += n;
    }
    return true;
}

bool LujarchConnection::present(uint32_t poolId, uint32_t index, uint32_t seq, FileDescriptor fence, const QList<QRect> &damage)
{
    const uint32_t damageCount = damage.size() > LJD_MAX_DAMAGE ? 0 : damage.size();
    QByteArray payload(sizeof(ljd_present) + damageCount * sizeof(ljd_rect), Qt::Uninitialized);
    ljd_present present{};
    present.pool_id = poolId;
    present.index = index;
    present.seq = seq;
    present.flags = fence.isValid() ? LJD_F_FENCE : 0;
    present.damage_count = damageCount;
    memcpy(payload.data(), &present, sizeof(present));
    for (uint32_t i = 0; i < damageCount; i++) {
        const ljd_rect rect{damage[i].x(), damage[i].y(), damage[i].width(), damage[i].height()};
        memcpy(payload.data() + sizeof(ljd_present) + i * sizeof(ljd_rect), &rect, sizeof(rect));
    }
    return sendMessage(LJD_PRESENT, payload.constData(), payload.size(), fence.isValid() ? fence.get() : -1);
}

bool LujarchConnection::requestFrame()
{
    return sendMessage(LJD_REQUEST_FRAME, nullptr, 0, -1);
}

} // namespace KWin

#include "moc_lujarch_connection.cpp"
