package com.music.laya.platform

import kotlinx.coroutines.CoroutineDispatcher

/** `Dispatchers.IO`, which common code cannot name (it is internal there). */
expect val ioDispatcher: CoroutineDispatcher
