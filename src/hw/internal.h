/*
 * ChDroid v7 - High-performance Container Runtime
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef CHDROID_HW_INTERNAL_H
#define CHDROID_HW_INTERNAL_H

/*
 * Unified GPU group bridge.
 *
 * Every hardware node mirrored into the container is chowned root:this group,
 * so GPU access works regardless of which host GID the node originally
 * carried (GID 44 for video, GID 1006 for render, etc.). Root opens the nodes
 * as their owner; any other user is added to the group with usermod.
 *
 * GID 786 is well outside the standard Debian/Ubuntu range (0-999 system,
 * 1000+ users) and has never been assigned to a named group in the FHS.
 */
#define CHDROID_GPU_GROUP_NAME "chdroid-gpu"
#define CHDROID_GPU_UNIFIED_GID 786

#endif /* CHDROID_HW_INTERNAL_H */
