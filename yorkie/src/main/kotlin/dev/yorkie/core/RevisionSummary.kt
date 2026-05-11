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

package dev.yorkie.core

import java.util.Date

/**
 * Represents a document revision for version management.
 * Stores a snapshot of document content at a specific point in time,
 * enabling rollback, audit, and version history tracking.
 */
public data class RevisionSummary(
    /** Unique identifier of the revision. */
    val id: String,
    /** User-friendly name for this revision. */
    val label: String,
    /** Detailed explanation of this revision. */
    val description: String,
    /** Serialized document content (JSON) at this revision point. */
    val snapshot: String,
    /** Time when this revision was created. */
    val createdAt: Date,
)
