object SupabaseClient {
    // ✅ Project URL
    private const val SUPABASE_URL = "https://eqddhfyoymgvdrttmatmh.supabase.co"
    
    // ✅ Anonymous Key (USE THIS ONE)
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVxZGRoZnlveW1ndmRydG1hdG1oIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc4NDE4MTgsImV4cCI6MjEwMzQxNzgxOH0.sqp9EJmwNG6-JuHnPsQyTxTnUAmoBL5D0aa8CJx1bQ0"

    val client = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_ANON_KEY  // ← Only this key
    ) {
        install(Postgrest)
        install(Storage)
        install(Realtime)
    }
}
