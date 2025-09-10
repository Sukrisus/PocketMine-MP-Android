package io.scer.pocketmine.screens.home

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
import android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import io.scer.pocketmine.R
import io.scer.pocketmine.server.Server
import io.scer.pocketmine.utils.AsyncRequest
import io.scer.pocketmine.utils.saveTo
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(), Handler.Callback {
    private var assemblies: HashMap<String, JSONObject>? = null
    private var job: Job? = null

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        
        // Setup navigation
        val host: NavHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        val navController = host.navController
        navigation.setupWithNavController(navController)

        // Setup system UI for modern Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = 
                FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        // Setup server files first
        setupServerFiles()
        
        // Then check permissions and initialize
        checkPermissionsAndInit()
    }

    private fun setupServerFiles() {
        val appDirectoryPath = applicationInfo.dataDir
        val externalDirectory = getExternalFilesDir("PocketMine-MP")?.path 
            ?: "${filesDir.absolutePath}/PocketMine-MP"

        // Ensure directory exists
        File(externalDirectory).mkdirs()

        Server.makeInstance(Server.Files(
            dataDirectory = File(externalDirectory),
            phar = File(externalDirectory, "PocketMine-MP.phar"),
            appDirectory = File(appDirectoryPath),
            php = File(appDirectoryPath, "php"),
            settingsFile = File(externalDirectory, "php.ini"),
            serverSetting = File(externalDirectory, "server.properties")
        ))

        // Copy PHP binary
        try {
            val phpFile = File(Server.getInstance().files.appDirectory, "php")
            if (phpFile.exists()) {
                phpFile.delete()
            }
            val targetFile = copyAsset("php")
            targetFile.setExecutable(true, false)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying PHP binary", e)
        }
    }

    private fun checkPermissionsAndInit() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ - Check for notification permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, 
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    init()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-12 - Check for storage permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, 
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 
                        PERMISSIONS_REQUEST_CODE
                    )
                } else {
                    init()
                }
            }
            else -> {
                // Android 5 and below
                init()
            }
        }
    }

    private fun init() {
        // Cancel any existing job
        job?.cancel()
        
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch assemblies in background thread
                assemblies = AsyncRequest().execute("stable", "beta", "development")
                
                withContext(Dispatchers.Main) {
                    // UI updates on main thread
                    setupServerDirectories()
                    
                    if (assemblies != null && !Server.getInstance().isInstalled) {
                        downloadPMBuild("stable")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error during initialization", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, 
                        "Failed to initialize: ${e.localizedMessage}", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupServerDirectories() {
        try {
            // Create temp directory
            File(Server.getInstance().files.dataDirectory, "tmp").mkdirs()
            
            // Create PHP ini file if it doesn't exist
            val ini = Server.getInstance().files.settingsFile
            if (!ini.exists()) {
                ini.createNewFile()
                ini.writeText("""
                    date.timezone=UTC
                    short_open_tag=0
                    asp_tags=0
                    phar.readonly=0
                    phar.require_hash=1
                    igbinary.compact_strings=0
                    zend.assertions=-1
                    error_reporting=-1
                    display_errors=1
                    display_startup_errors=1
                """.trimIndent())
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error setting up server directories", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE, NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    init()
                } else {
                    // Show explanation and offer to continue with limited functionality
                    AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("The app needs permissions to function properly. Some features may not work without them.")
                        .setPositiveButton("Continue Anyway") { _, _ -> init() }
                        .setNegativeButton("Exit") { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    private fun downloadFile(url: String, file: File) {
        if (file.exists()) file.delete()
        
        // Use coroutines for downloads instead of raw threads
        CoroutineScope(Dispatchers.IO).launch {
            var dialog: AlertDialog? = null
            
            withContext(Dispatchers.Main) {
                val view = View.inflate(this@MainActivity, R.layout.download, null)
                dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.downloading, file.name))
                    .setCancelable(false)
                    .setView(view)
                    .create()
                dialog?.show()
            }

            try {
                URL(url).saveTo(file)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Download completed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Download failed", e)
                withContext(Dispatchers.Main) {
                    Snackbar.make(findViewById(android.R.id.content), 
                        R.string.download_error, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dialog?.dismiss()
                }
            }
        }
    }

    override fun handleMessage(message: Message): Boolean {
        return false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.download -> {
                downloadPM()
                true
            }
            R.id.kill -> {
                Server.getInstance().kill()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Throws(IOException::class)
    private fun copyAsset(path: String): File {
        val file = File(Server.getInstance().files.appDirectory, path)
        applicationContext.assets.open(path).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    private fun downloadPM() {
        if (assemblies == null) {
            Snackbar.make(findViewById(android.R.id.content), 
                R.string.assemblies_error, Snackbar.LENGTH_LONG).show()
            return
        }
        
        if (Server.getInstance().isRunning) {
            Server.getInstance().kill()
        }

        val builds = assemblies!!.keys.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.select_channel)
            .setItems(builds) { _, index ->
                val channel = builds[index]
                val json = assemblies!![channel]
                showBuildInfo(channel, json)
            }
            .create()
            .show()
    }

    private fun showBuildInfo(channel: String, json: JSONObject?) {
        try {
            val view = View.inflate(this, R.layout.build_info, null)

            json?.let {
                if (it.optBoolean("is_dev", false)) {
                    view.findViewById<TextView>(R.id.development_build).visibility = View.VISIBLE
                }

                view.findViewById<TextView>(R.id.api).text = it.optString("base_version", "Unknown")
                view.findViewById<TextView>(R.id.build_number).text = it.optString("build_number", "Unknown")
                view.findViewById<TextView>(R.id.branch).text = it.optString("branch", "Unknown")
                view.findViewById<TextView>(R.id.game_version).text = it.optString("mcpe_version", "Unknown")
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.build_info)
                .setView(view)
                .setPositiveButton(R.string.download) { _, _ ->
                    downloadPMBuild(channel)
                }
                .setNegativeButton("Cancel", null)
                .create()
                .show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing build info", e)
            Toast.makeText(this, "Error displaying build info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadPMBuild(channel: String) {
        try {
            val json = assemblies!![channel]
            val downloadUrl = json?.getString("download_url")
            if (downloadUrl != null) {
                downloadFile(downloadUrl, Server.getInstance().files.phar)
            } else {
                Snackbar.make(findViewById(android.R.id.content), 
                    "Download URL not found", Snackbar.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error downloading PM build", e)
            Snackbar.make(findViewById(android.R.id.content), 
                R.string.assemblies_error, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}