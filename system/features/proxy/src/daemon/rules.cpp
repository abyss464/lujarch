// Copyright (C) 2026 The Lujarch Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "rules.h"

#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <errno.h>
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>

namespace lujarch::proxy::rules {

namespace {

using android::base::unique_fd;

// Before netd's first rule (Android's rules start at 9000 with the local table's).
constexpr uint32_t kBlockPriority = 8999;
constexpr uint32_t kExceptionPriority = 8998;
// Where an exception continues: a rule of the block's own just before Android's (from 10000).
// A goto whose target priority holds no rule is skipped, and Android has a rule at 10000 only
// while a VPN runs.
constexpr uint32_t kContinueAt = 9999;
constexpr size_t kMaxExceptions = 32;

std::atomic<uint32_t> sequence{1};

struct Request {
    nlmsghdr header;
    fib_rule_hdr rule;
    char attributes[128];
};

void AddAttribute(Request* request, uint16_t type, const void* data, size_t size) {
    auto* attr = reinterpret_cast<nlattr*>(reinterpret_cast<char*>(request) +
                                           NLMSG_ALIGN(request->header.nlmsg_len));
    attr->nla_type = type;
    attr->nla_len = NLA_HDRLEN + size;
    memcpy(reinterpret_cast<char*>(attr) + NLA_HDRLEN, data, size);
    request->header.nlmsg_len = NLMSG_ALIGN(request->header.nlmsg_len) + NLA_ALIGN(attr->nla_len);
}

// Sends one rule request; returns 0 or the kernel's errno.
int Send(Request* request) {
    unique_fd fd(socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE));
    if (fd < 0) return errno;
    request->header.nlmsg_seq = sequence++;
    sockaddr_nl kernel = {.nl_family = AF_NETLINK};
    if (TEMP_FAILURE_RETRY(sendto(fd.get(), request, request->header.nlmsg_len, 0,
                                  reinterpret_cast<sockaddr*>(&kernel), sizeof(kernel))) < 0) {
        return errno;
    }
    char reply[512];
    ssize_t n = TEMP_FAILURE_RETRY(recv(fd.get(), reply, sizeof(reply), 0));
    if (n < 0) return errno;
    auto* header = reinterpret_cast<nlmsghdr*>(reply);
    if (!NLMSG_OK(header, static_cast<size_t>(n)) || header->nlmsg_type != NLMSG_ERROR) {
        return EPROTO;
    }
    return -reinterpret_cast<nlmsgerr*>(NLMSG_DATA(header))->error;
}

Request NewRequest(uint16_t type, uint32_t priority) {
    Request request = {};
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(fib_rule_hdr));
    request.header.nlmsg_type = type;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    if (type == RTM_NEWRULE) request.header.nlmsg_flags |= NLM_F_CREATE | NLM_F_EXCL;
    request.rule.family = AF_INET6;
    request.rule.table = RT_TABLE_UNSPEC;
    AddAttribute(&request, FRA_PRIORITY, &priority, sizeof(priority));
    return request;
}

void DeleteAll(uint32_t priority) {
    for (;;) {
        Request request = NewRequest(RTM_DELRULE, priority);
        int error = Send(&request);
        if (error == ENOENT) return;
        if (error != 0) {
            LOG(ERROR) << "cannot remove the IPv6 rule at " << priority << ": " << strerror(error);
            return;
        }
    }
}

}  // namespace

void BlockIpv6(const std::vector<in6_addr>& exceptions) {
    DeleteAll(kBlockPriority);
    Request block = NewRequest(RTM_NEWRULE, kBlockPriority);
    block.rule.action = FR_ACT_PROHIBIT;
    block.rule.dst_len = 3;
    in6_addr global = {};
    global.s6_addr[0] = 0x20;
    AddAttribute(&block, FRA_DST, &global, sizeof(global));
    if (int error = Send(&block); error != 0) {
        LOG(ERROR) << "cannot block IPv6: " << strerror(error);
    }
    DeleteAll(kContinueAt);
    Request landing = NewRequest(RTM_NEWRULE, kContinueAt);
    landing.rule.action = FR_ACT_NOP;
    if (int error = Send(&landing); error != 0) {
        LOG(ERROR) << "IPv6 block: no rule for the exceptions to continue at: " << strerror(error);
    }
    SetIpv6Exceptions(exceptions);
}

void SetIpv6Exceptions(const std::vector<in6_addr>& exceptions) {
    DeleteAll(kExceptionPriority);
    size_t count = 0;
    for (const in6_addr& address : exceptions) {
        if (count++ == kMaxExceptions) {
            LOG(WARNING) << "IPv6 block: more than " << kMaxExceptions
                         << " nodes with an IPv6 address; the others stay unreachable";
            break;
        }
        Request exception = NewRequest(RTM_NEWRULE, kExceptionPriority);
        exception.rule.action = FR_ACT_GOTO;
        exception.rule.dst_len = 128;
        AddAttribute(&exception, FRA_DST, &address, sizeof(address));
        fib_rule_uid_range root = {.start = 0, .end = 0};
        AddAttribute(&exception, FRA_UID_RANGE, &root, sizeof(root));
        AddAttribute(&exception, FRA_GOTO, &kContinueAt, sizeof(kContinueAt));
        if (int error = Send(&exception); error != 0) {
            LOG(ERROR) << "IPv6 block: no exception for a node's IPv6 address: " << strerror(error);
        }
    }
}

void UnblockIpv6() {
    DeleteAll(kExceptionPriority);
    DeleteAll(kBlockPriority);
    DeleteAll(kContinueAt);
}

}  // namespace lujarch::proxy::rules
