package me.capcom.smsgateway.modules.grafana

import me.capcom.smsgateway.modules.settings.PreferencesStorage
import org.koin.dsl.module

val grafanaModule = module {
    single { GrafanaAlertStore(PreferencesStorage(get(), "grafana")) }
    single { GrafanaAlertNotifier(get()) }
    single { GrafanaAlertsService(get(), get()) }
    single { GrafanaAlertsDiscovery(get(), get(), get()) }
}
