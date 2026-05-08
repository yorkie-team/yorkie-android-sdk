/*
 * Copyright 2025 The Yorkie Authors. All rights reserved.
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

package dev.yorkie.api

import dev.yorkie.core.RevisionSummary
import java.util.Date
import dev.yorkie.api.v1.RevisionSummary as PbRevisionSummary

/**
 * Converts a protobuf [PbRevisionSummary] to a [RevisionSummary].
 */
internal fun PbRevisionSummary.toRevisionSummary(): RevisionSummary {
    return RevisionSummary(
        id = id,
        label = label,
        description = description,
        snapshot = snapshot,
        createdAt = if (hasCreatedAt()) Date(createdAt.seconds * 1_000) else Date(),
    )
}
