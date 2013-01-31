/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
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
 *
 */

package com.hazelcast.client.proxy.listener;

import com.hazelcast.client.impl.DataAwareItemEvent;
import com.hazelcast.core.ItemEvent;
import com.hazelcast.core.ItemEventType;
import com.hazelcast.core.ItemListener;
import com.hazelcast.core.Member;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Protocol;
import com.hazelcast.nio.protocol.Command;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.SerializationService;

public class ItemEventLRH<E> implements ListenerResponseHandler {
    private ItemListener<E> listener;

    public ItemEventLRH(ItemListener<E> listener) {
        this.listener = listener;
    }

    public void handleResponse(Protocol response, SerializationService ss) throws Exception {
        if (Command.EVENT.equals(response.command)) {
            String eventType = response.args[2];
            String name = response.args[1];
            String[] address = response.args[3].split(":");
            Member source = new MemberImpl(new Address(address[0], Integer.valueOf(address[1])), false);
            Data item = response.buffers[0];
            ItemEventType itemEventType = ItemEventType.valueOf(eventType);
            ItemEvent event = new DataAwareItemEvent(name, itemEventType, item, source, ss);
            switch (itemEventType) {
                case ADDED:
                    listener.itemAdded(event);
                    break;
                case REMOVED:
                    listener.itemRemoved(event);
                    break;
            }
        } else {
            throw new RuntimeException(response.args[0]);
        }
    }
}