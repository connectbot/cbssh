/*
 * ConnectBot SSH Library
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.sshlib.client

import org.connectbot.sshlib.protocol.ByteString
import java.nio.ByteBuffer

internal fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

// Stock Kaitai owns the parsed array; the handoff adds no second payload copy.
internal fun ByteString.asReadOnlyBuffer(): ByteBuffer = ByteBuffer.wrap(data()).asReadOnlyBuffer()
