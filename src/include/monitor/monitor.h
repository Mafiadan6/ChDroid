/*
 * ChDroid v7 - High-performance Container Runtime
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef CHDROID_MONITOR_MONITOR_H
#define CHDROID_MONITOR_MONITOR_H

/* monitor.c */
void ds_monitor_run(struct ds_config *cfg, int sync_pipe_write);

#endif /* CHDROID_MONITOR_MONITOR_H */
