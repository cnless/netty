/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel.epoll;

import io.netty5.buffer.api.Buffer;
import io.netty5.channel.ChannelException;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.GlobalEventExecutor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty5.channel.epoll.LinuxSocket.newSocketStream;
import static io.netty5.channel.epoll.Native.IS_SUPPORTING_TCP_FASTOPEN_CLIENT;

/**
 * {@link SocketChannel} implementation that uses linux EPOLL Edge-Triggered Mode for
 * maximal performance.
 */
public final class EpollSocketChannel
        extends AbstractEpollStreamChannel<EpollServerSocketChannel, SocketAddress, SocketAddress>
        implements SocketChannel {

    private final EpollSocketChannelConfig config;

    private volatile Collection<InetAddress> tcpMd5SigAddresses = Collections.emptyList();

    public EpollSocketChannel(EventLoop eventLoop) {
        this(eventLoop, null);
    }

    public EpollSocketChannel(EventLoop eventLoop, ProtocolFamily protocolFamily) {
        super(eventLoop, newSocketStream(protocolFamily), false);
        config = new EpollSocketChannelConfig(this);
    }

    public EpollSocketChannel(EventLoop eventLoop, int fd) {
        super(eventLoop, fd);
        config = new EpollSocketChannelConfig(this);
    }

    EpollSocketChannel(EpollServerSocketChannel parent, EventLoop eventLoop,
                       LinuxSocket fd, InetSocketAddress remoteAddress) {
        super(parent, eventLoop, fd, remoteAddress);
        config = new EpollSocketChannelConfig(this);

        if (parent != null) {
            tcpMd5SigAddresses = parent.tcpMd5SigAddresses();
        }
    }

    /**
     * Returns the {@code TCP_INFO} for the current socket.
     * See <a href="https://linux.die.net//man/7/tcp">man 7 tcp</a>.
     */
    public EpollTcpInfo tcpInfo() {
        return tcpInfo(new EpollTcpInfo());
    }

    /**
     * Updates and returns the {@code TCP_INFO} for the current socket.
     * See <a href="https://linux.die.net//man/7/tcp">man 7 tcp</a>.
     */
    public EpollTcpInfo tcpInfo(EpollTcpInfo info) {
        try {
            socket.getTcpInfo(info);
            return info;
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public EpollSocketChannelConfig config() {
        return config;
    }

    @Override
    boolean doConnect0(SocketAddress remote) throws Exception {
        if (IS_SUPPORTING_TCP_FASTOPEN_CLIENT && config.isTcpFastOpenConnect()) {
            ChannelOutboundBuffer outbound = outboundBuffer();
            outbound.addFlush();
            Object curr = outbound.current();
            if (curr instanceof Buffer) {
                // If no cookie is present, the write fails with EINPROGRESS and this call basically
                // becomes a normal async connect. All writes will be sent normally afterwards.
                final long localFlushedAmount;
                Buffer initialData = (Buffer) curr;
                localFlushedAmount = doWriteOrSendBytes(initialData, (InetSocketAddress) remote, true);
                if (localFlushedAmount > 0) {
                    // We had a cookie and our fast-open proceeded. Remove written data
                    // then continue with normal TCP operation.
                    outbound.removeBytes(localFlushedAmount);
                    return true;
                }
            }
        }
        return super.doConnect0(remote);
    }

    @Override
    protected Future<Executor> prepareToClose() {
        try {
            // Check isOpen() first as otherwise it will throw a RuntimeException
            // when call getSoLinger() as the fd is not valid anymore.
            if (isOpen() && config().getSoLinger() > 0) {
                // We need to cancel this key of the channel so we may not end up in a eventloop spin
                // because we try to read or write until the actual close happens which may be later due
                // SO_LINGER handling.
                // See https://github.com/netty/netty/issues/4449
                executor().deregisterForIo(this).map(v -> GlobalEventExecutor.INSTANCE);
            }
        } catch (Throwable ignore) {
            // Ignore the error as the underlying channel may be closed in the meantime and so
            // getSoLinger() may produce an exception. In this case we just return null.
            // See https://github.com/netty/netty/issues/4449
        }
        return null;
    }

    void setTcpMd5Sig(Map<InetAddress, byte[]> keys) throws IOException {
        tcpMd5SigAddresses = TcpMd5Util.newTcpMd5Sigs(this, tcpMd5SigAddresses, keys);
    }
}
