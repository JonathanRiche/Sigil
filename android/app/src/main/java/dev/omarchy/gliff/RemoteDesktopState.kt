package dev.omarchy.gliff

import org.json.JSONObject

data class RemoteApp(val address: String,val app: String,val title: String,val workspace: Int,val monitor: Int)
data class RemoteOutput(val name: String,val width: Int,val height: Int,val focused: Boolean)
data class RemoteWorkspace(val id: Int,val name: String)
data class RemoteDesktopState(
    val apps: List<RemoteApp>, val outputs: List<RemoteOutput>, val workspaces: List<RemoteWorkspace>,
    val battery: String?, val capabilities: Set<String>
) {
    companion object {
        fun parse(text: String): RemoteDesktopState {
            require(text.toByteArray(Charsets.UTF_8).size <= 65536)
            val root = JSONObject(text)
            fun clean(s: String) = s.filterNot { it.isISOControl() }.take(256)
            val clients = root.optJSONArray("clients")
            val apps = (0 until minOf(clients?.length() ?: 0,128)).mapNotNull { i ->
                val c = clients!!.optJSONObject(i) ?: return@mapNotNull null
                val address = c.optString("address")
                if (!address.matches(Regex("0x[0-9a-fA-F]{1,16}"))) return@mapNotNull null
                val workspace = c.opt("workspace")
                val id = if (workspace is JSONObject) workspace.optInt("id") else c.optInt("workspace")
                RemoteApp(address,clean(c.optString("class")),clean(c.optString("title")),id,c.optInt("monitor"))
            }
            val monitors = root.optJSONArray("outputs")
            val outputs = (0 until minOf(monitors?.length() ?: 0,32)).mapNotNull { i ->
                val o = monitors!!.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))) return@mapNotNull null
                RemoteOutput(name,o.optInt("width"),o.optInt("height"),o.optBoolean("focused"))
            }
            val spaces = root.optJSONArray("workspaces")
            val workspaces = (0 until minOf(spaces?.length() ?: 0,128)).mapNotNull { i ->
                val w = spaces!!.optJSONObject(i) ?: return@mapNotNull null
                val id = w.optInt("id"); if (id < 0) null else RemoteWorkspace(id,clean(w.optString("name")))
            }
            val b = root.optJSONObject("battery")
            val percent = b?.opt("percent") as? Number
            val battery = percent?.toInt()?.takeIf { it in 0..100 }?.let { "$it%" }
            val caps = root.optJSONObject("capabilities")
            val capabilities = listOf("overview","launcher","volume","brightness","media","text").filter { caps?.optBoolean(it,false) == true }.toSet()
            return RemoteDesktopState(apps,outputs,workspaces,battery,capabilities)
        }
    }
}
