package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.noop.NoopTimer

val noopTimer = NoopTimer(Meter.Id("", Tags.empty(), null, null, Meter.Type.TIMER))
