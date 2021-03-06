/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron;

import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.status.AtomicCounter;

/**
 * Counter stored in the counters file managed by the media driver which can be read with AeronStat.
 */
public class Counter extends AtomicCounter
{
    private final long registrationId;
    private final ClientConductor clientConductor;
    private volatile boolean isClosed = false;

    Counter(
        final long registrationId,
        final ClientConductor clientConductor,
        final AtomicBuffer buffer,
        final int counterId)
    {
        super(buffer, counterId);

        this.registrationId = registrationId;
        this.clientConductor = clientConductor;
    }

    /**
     * Return the registration id used to register this counter with the media driver.
     *
     * @return registration id
     */
    public long registrationId()
    {
        return registrationId;
    }

    /**
     * Close the counter which will release the resource managed by the media driver.
     * <p>
     * This method is idempotent.
     */
    public void close()
    {
        clientConductor.clientLock().lock();
        try
        {
            if (!isClosed)
            {
                super.close();
                isClosed = true;

                clientConductor.releaseCounter(this);
            }
        }
        finally
        {
            clientConductor.clientLock().unlock();
        }
    }

    /**
     * Has this object been closed and should no longer be used?
     *
     * @return true if it has been closed otherwise false.
     */
    public boolean isClosed()
    {
        return isClosed;
    }

    /**
     * Forcibly close the counter and release resources.
     */
    void forceClose()
    {
        if (!isClosed)
        {
            super.close();
            isClosed = true;
            clientConductor.asyncReleaseCounter(this);
        }
    }
}
