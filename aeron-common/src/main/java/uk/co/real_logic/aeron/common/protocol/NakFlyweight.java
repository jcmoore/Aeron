/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.common.protocol;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Flyweight for a Nak Packet
 *
 * <a href="https://github.com/real-logic/Aeron/wiki/Protocol-Specification#data-recovery-via-retransmit-request">Data Recovery</a>
 */
public class    NakFlyweight extends HeaderFlyweight
{
    public static final int HEADER_LENGTH = 28;

    private static final int SESSION_ID_FIELD_OFFSET = 8;
    private static final int CHANNEL_ID_FIELD_OFFSET = 12;
    private static final int TERM_ID_FIELD_OFFSET = 16;
    private static final int TERM_OFFSET_FIELD_OFFSET = 20;
    private static final int LENGTH_FIELD_OFFSET = 24;

    /**
     * return session id field
     * @return session id field
     */
    public int sessionId()
    {
        return atomicBuffer().getInt(offset() + SESSION_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set session id field
     * @param sessionId field value
     * @return flyweight
     */
    public NakFlyweight sessionId(final int sessionId)
    {
        atomicBuffer().putInt(offset() + SESSION_ID_FIELD_OFFSET, sessionId, LITTLE_ENDIAN);
        return this;
    }

    /**
     * return channel id field
     *
     * @return channel id field
     */
    public int channelId()
    {
        return atomicBuffer().getInt(offset() + CHANNEL_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set channel id field
     *
     * @param channelId field value
     * @return flyweight
     */
    public NakFlyweight channelId(final int channelId)
    {
        atomicBuffer().putInt(offset() + CHANNEL_ID_FIELD_OFFSET, channelId, LITTLE_ENDIAN);
        return this;
    }

    /**
     * return term id field
     * @return term id field
     */
    public int termId()
    {
        return atomicBuffer().getInt(offset() + TERM_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set term id field
     * @param termId field value
     * @return flyweight
     */
    public NakFlyweight termId(final int termId)
    {
        atomicBuffer().putInt(offset() + TERM_ID_FIELD_OFFSET, termId, LITTLE_ENDIAN);
        return this;
    }

    /**
     * return term offset field
     * @return term offset field
     */
    public int termOffset()
    {
        return atomicBuffer().getInt(offset() + TERM_OFFSET_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set term offset field
     * @param termOffset field value
     * @return flyweight
     */
    public NakFlyweight termOffset(final int termOffset)
    {
        atomicBuffer().putInt(offset() + TERM_OFFSET_FIELD_OFFSET, termOffset, LITTLE_ENDIAN);
        return this;
    }

    /**
     * The length field
     *
     * @return length field
     */
    public int length()
    {
        return atomicBuffer().getInt(offset() + LENGTH_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set length field
     * @param length field value
     * @return flyweight
     */
    public NakFlyweight length(final int length)
    {
        atomicBuffer().putInt(offset() + LENGTH_FIELD_OFFSET, length, LITTLE_ENDIAN);
        return this;
    }
}