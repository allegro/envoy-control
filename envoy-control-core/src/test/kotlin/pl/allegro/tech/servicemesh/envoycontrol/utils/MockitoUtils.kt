package pl.allegro.tech.servicemesh.envoycontrol.utils

import org.mockito.Mockito

fun <T> any(type: Class<T>): T = Mockito.any<T>(type)
