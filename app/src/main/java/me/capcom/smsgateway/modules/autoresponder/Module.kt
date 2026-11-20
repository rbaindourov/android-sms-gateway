package me.capcom.smsgateway.modules.autoresponder

import org.koin.dsl.module

val autoresponderModule = module {
    single { AutoResponderStore(get()) }
    single { AutoResponderNotifier(get()) }
    single<ContactLookup> { AndroidContactLookup(get()) }
    single { AutoResponderService(get(), get(), get(), get(), get(), get(), get()) }
}

const val MODULE_NAME = "autoresponder"
