/*
 * Copyright 2012 The Netty Project
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
package io.netty5.channel;

import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.DefaultBufferAllocators;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty5.channel.ChannelOption.ALLOW_HALF_CLOSURE;
import static io.netty5.channel.ChannelOption.AUTO_CLOSE;
import static io.netty5.channel.ChannelOption.AUTO_READ;
import static io.netty5.channel.ChannelOption.BUFFER_ALLOCATOR;
import static io.netty5.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty5.channel.ChannelOption.MAX_MESSAGES_PER_READ;
import static io.netty5.channel.ChannelOption.MAX_MESSAGES_PER_WRITE;
import static io.netty5.channel.ChannelOption.MESSAGE_SIZE_ESTIMATOR;
import static io.netty5.channel.ChannelOption.RCVBUF_ALLOCATOR;
import static io.netty5.channel.ChannelOption.WRITE_BUFFER_WATER_MARK;
import static io.netty5.channel.ChannelOption.WRITE_SPIN_COUNT;
import static io.netty5.util.internal.ObjectUtil.checkPositive;
import static io.netty5.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.util.Objects.requireNonNull;

/**
 * The default {@link ChannelConfig} implementation.
 */
public class DefaultChannelConfig implements ChannelConfig {
    private static final MessageSizeEstimator DEFAULT_MSG_SIZE_ESTIMATOR = DefaultMessageSizeEstimator.DEFAULT;

    private static final int DEFAULT_CONNECT_TIMEOUT = 30000;

    private static final AtomicIntegerFieldUpdater<DefaultChannelConfig> AUTOREAD_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(DefaultChannelConfig.class, "autoRead");
    private static final AtomicReferenceFieldUpdater<DefaultChannelConfig, WriteBufferWaterMark> WATERMARK_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    DefaultChannelConfig.class, WriteBufferWaterMark.class, "writeBufferWaterMark");

    protected final Channel channel;

    private volatile BufferAllocator bufferAllocator = DefaultBufferAllocators.preferredAllocator();
    private volatile RecvBufferAllocator rcvBufAllocator;
    private volatile MessageSizeEstimator msgSizeEstimator = DEFAULT_MSG_SIZE_ESTIMATOR;

    private volatile int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT;
    private volatile int writeSpinCount = 16;
    private volatile int maxMessagesPerWrite = Integer.MAX_VALUE;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int autoRead = 1;
    private volatile boolean autoClose = true;
    private volatile WriteBufferWaterMark writeBufferWaterMark = WriteBufferWaterMark.DEFAULT;
    private volatile boolean allowHalfClosure;

    public DefaultChannelConfig(Channel channel) {
        this(channel, new AdaptiveRecvBufferAllocator());
    }

    protected DefaultChannelConfig(Channel channel, RecvBufferAllocator allocator) {
        setRecvBufferAllocator(allocator, channel.metadata());
        this.channel = channel;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                null,
                CONNECT_TIMEOUT_MILLIS, MAX_MESSAGES_PER_READ, WRITE_SPIN_COUNT,
                BUFFER_ALLOCATOR, AUTO_READ, AUTO_CLOSE, RCVBUF_ALLOCATOR, WRITE_BUFFER_WATER_MARK,
                MESSAGE_SIZE_ESTIMATOR, MAX_MESSAGES_PER_WRITE, ALLOW_HALF_CLOSURE);
    }

    protected Map<ChannelOption<?>, Object> getOptions(
            Map<ChannelOption<?>, Object> result, ChannelOption<?>... options) {
        if (result == null) {
            result = new IdentityHashMap<>();
        }
        for (ChannelOption<?> o: options) {
            result.put(o, getOption(o));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean setOptions(Map<ChannelOption<?>, ?> options) {
        requireNonNull(options, "options");

        boolean setAllOptions = true;
        for (Entry<ChannelOption<?>, ?> e: options.entrySet()) {
            if (!setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                setAllOptions = false;
            }
        }

        return setAllOptions;
    }

    @Override
    @SuppressWarnings({ "unchecked", "deprecation" })
    public <T> T getOption(ChannelOption<T> option) {
        requireNonNull(option, "option");

        if (option == CONNECT_TIMEOUT_MILLIS) {
            return (T) Integer.valueOf(getConnectTimeoutMillis());
        }
        if (option == MAX_MESSAGES_PER_READ) {
            return (T) Integer.valueOf(getMaxMessagesPerRead());
        }
        if (option == WRITE_SPIN_COUNT) {
            return (T) Integer.valueOf(getWriteSpinCount());
        }
        if (option == BUFFER_ALLOCATOR) {
            return (T) getBufferAllocator();
        }
        if (option == RCVBUF_ALLOCATOR) {
            return getRecvBufferAllocator();
        }
        if (option == AUTO_READ) {
            return (T) Boolean.valueOf(isAutoRead());
        }
        if (option == AUTO_CLOSE) {
            return (T) Boolean.valueOf(isAutoClose());
        }
        if (option == WRITE_BUFFER_WATER_MARK) {
            return (T) getWriteBufferWaterMark();
        }
        if (option == MESSAGE_SIZE_ESTIMATOR) {
            return (T) getMessageSizeEstimator();
        }
        if (option == MAX_MESSAGES_PER_WRITE) {
            return (T) Integer.valueOf(getMaxMessagesPerWrite());
        }
        if (option == ALLOW_HALF_CLOSURE) {
            return (T) Boolean.valueOf(isAllowHalfClosure());
        }

        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == CONNECT_TIMEOUT_MILLIS) {
            setConnectTimeoutMillis((Integer) value);
        } else if (option == MAX_MESSAGES_PER_READ) {
            setMaxMessagesPerRead((Integer) value);
        } else if (option == WRITE_SPIN_COUNT) {
            setWriteSpinCount((Integer) value);
        } else if (option == BUFFER_ALLOCATOR) {
            setBufferAllocator((BufferAllocator) value);
        } else if (option == RCVBUF_ALLOCATOR) {
            setRecvBufferAllocator((RecvBufferAllocator) value);
        } else if (option == AUTO_READ) {
            setAutoRead((Boolean) value);
        } else if (option == AUTO_CLOSE) {
            setAutoClose((Boolean) value);
        } else if (option == WRITE_BUFFER_WATER_MARK) {
            setWriteBufferWaterMark((WriteBufferWaterMark) value);
        } else if (option == MESSAGE_SIZE_ESTIMATOR) {
            setMessageSizeEstimator((MessageSizeEstimator) value);
        } else if (option == MAX_MESSAGES_PER_WRITE) {
            setMaxMessagesPerWrite((Integer) value);
        } else if (option == ALLOW_HALF_CLOSURE) {
            setAllowHalfClosure((Boolean) value);
        } else {
            return false;
        }

        return true;
    }

    protected <T> void validate(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        option.validate(value);
    }

    @Override
    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    @Override
    public ChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        checkPositiveOrZero(connectTimeoutMillis, "connectTimeoutMillis");
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * @throws IllegalStateException if {@link #getRecvBufferAllocator()} does not return an object of type
     * {@link MaxMessagesRecvBufferAllocator}.
     */
    @Override
    @Deprecated
    public int getMaxMessagesPerRead() {
        try {
            MaxMessagesRecvBufferAllocator allocator = getRecvBufferAllocator();
            return allocator.maxMessagesPerRead();
        } catch (ClassCastException e) {
            throw new IllegalStateException("getRecvBufferAllocator() must return an object of type " +
                    "MaxMessagesRecvBufferAllocator", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * @throws IllegalStateException if {@link #getRecvBufferAllocator()} does not return an object of type
     * {@link MaxMessagesRecvBufferAllocator}.
     */
    @Override
    @Deprecated
    public ChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) {
        try {
            MaxMessagesRecvBufferAllocator allocator = getRecvBufferAllocator();
            allocator.maxMessagesPerRead(maxMessagesPerRead);
            return this;
        } catch (ClassCastException e) {
            throw new IllegalStateException("getRecvBufferAllocator() must return an object of type " +
                    "MaxMessagesRecvBufferAllocator", e);
        }
    }

    /**
     * Get the maximum number of message to write per eventloop run. Once this limit is
     * reached we will continue to process other events before trying to write the remaining messages.
     */
    public int getMaxMessagesPerWrite() {
        return maxMessagesPerWrite;
    }

     /**
     * Set the maximum number of message to write per eventloop run. Once this limit is
     * reached we will continue to process other events before trying to write the remaining messages.
     */
    public ChannelConfig setMaxMessagesPerWrite(int maxMessagesPerWrite) {
        this.maxMessagesPerWrite = checkPositive(maxMessagesPerWrite, "maxMessagesPerWrite");
        return this;
    }

    @Override
    public int getWriteSpinCount() {
        return writeSpinCount;
    }

    @Override
    public ChannelConfig setWriteSpinCount(int writeSpinCount) {
        checkPositive(writeSpinCount, "writeSpinCount");
        // Integer.MAX_VALUE is used as a special value in the channel implementations to indicate the channel cannot
        // accept any more data, and results in the writeOp being set on the selector (or execute a runnable which tries
        // to flush later because the writeSpinCount quantum has been exhausted). This strategy prevents additional
        // conditional logic in the channel implementations, and shouldn't be noticeable in practice.
        if (writeSpinCount == Integer.MAX_VALUE) {
            --writeSpinCount;
        }
        this.writeSpinCount = writeSpinCount;
        return this;
    }

    @Override
    public BufferAllocator getBufferAllocator() {
        return bufferAllocator;
    }

    @Override
    public ChannelConfig setBufferAllocator(BufferAllocator bufferAllocator) {
        requireNonNull(bufferAllocator, "bufferAllocator");
        this.bufferAllocator = bufferAllocator;
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RecvBufferAllocator> T getRecvBufferAllocator() {
        return (T) rcvBufAllocator;
    }

    @Override
    public ChannelConfig setRecvBufferAllocator(RecvBufferAllocator allocator) {
        rcvBufAllocator = requireNonNull(allocator, "allocator");
        return this;
    }

    /**
     * Set the {@link RecvBufferAllocator} which is used for the channel to allocate receive buffers.
     * @param allocator the allocator to set.
     * @param metadata Used to set the {@link ChannelMetadata#defaultMaxMessagesPerRead()} if {@code allocator}
     * is of type {@link MaxMessagesRecvBufferAllocator}.
     */
    private void setRecvBufferAllocator(RecvBufferAllocator allocator, ChannelMetadata metadata) {
        requireNonNull(allocator, "allocator");
        requireNonNull(metadata, "metadata");
        if (allocator instanceof MaxMessagesRecvBufferAllocator) {
            ((MaxMessagesRecvBufferAllocator) allocator).maxMessagesPerRead(metadata.defaultMaxMessagesPerRead());
        }
        setRecvBufferAllocator(allocator);
    }

    @Override
    public boolean isAutoRead() {
        return autoRead == 1;
    }

    @Override
    public ChannelConfig setAutoRead(boolean autoRead) {
        boolean oldAutoRead = AUTOREAD_UPDATER.getAndSet(this, autoRead ? 1 : 0) == 1;
        if (autoRead && !oldAutoRead) {
            channel.read();
        } else if (!autoRead && oldAutoRead) {
            autoReadCleared();
        }
        return this;
    }

    /**
     * Is called once {@link #setAutoRead(boolean)} is called with {@code false} and {@link #isAutoRead()} was
     * {@code true} before.
     */
    protected void autoReadCleared() { }

    @Override
    public boolean isAutoClose() {
        return autoClose;
    }

    @Override
    public ChannelConfig setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
        return this;
    }

    @Override
    public ChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        this.writeBufferWaterMark = requireNonNull(writeBufferWaterMark, "writeBufferWaterMark");
        return this;
    }

    @Override
    public WriteBufferWaterMark getWriteBufferWaterMark() {
        return writeBufferWaterMark;
    }

    @Override
    public MessageSizeEstimator getMessageSizeEstimator() {
        return msgSizeEstimator;
    }

    @Override
    public ChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
        requireNonNull(estimator, "estimator");
        msgSizeEstimator = estimator;
        return this;
    }

    @Override
    public boolean isAllowHalfClosure() {
        return allowHalfClosure;
    }

    @Override
    public ChannelConfig setAllowHalfClosure(boolean allowHalfClosure) {
        this.allowHalfClosure = allowHalfClosure;
        return this;
    }
}
