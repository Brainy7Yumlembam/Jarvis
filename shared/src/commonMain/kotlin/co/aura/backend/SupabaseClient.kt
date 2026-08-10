package co.aura.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

fun createAuraSupabaseClient(url: String, key: String): SupabaseClient {
    return createSupabaseClient(url, key) {
        install(Postgrest)
        install(Auth)
        install(Realtime)
    }
}
