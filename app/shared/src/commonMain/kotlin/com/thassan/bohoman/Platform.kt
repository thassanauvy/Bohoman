package com.thassan.bohoman

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform