package fuck.system.hashcat

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import fuck.system.hashcat.databinding.ActivityMainBinding
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Hosts the Hashcat, CAP converter and log screens, applies the persisted light
 * or dark theme and application language, accepts hash input from files or the
 * clipboard and forwards native Hashcat output to the log screen.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity()
{
    private data class HashcatStatus(
        val state: String? = null,
        val progress: String? = null,
        val currentKey: String? = null,
        val runtime: String? = null,
        val eta: String? = null,
        val speed: String? = null,
        val recovered: String? = null
    )

    private enum class HashcatRunState
    {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var hashcatRunner: HashcatRunner

    private var selectedCaptureUri: Uri? = null
    private var selectedHashUri: Uri? = null
    private var selectedHashClipboardFile: File? = null
    private var selectedWordlistUri: Uri? = null
    private var pendingConversion: CapToHc22000Converter.Result? = null
    private var selectedScreenId: Int = R.id.nav_hashcat
    private var hashModes: List<HashcatRunner.HashMode> = emptyList()
    private var selectedHashMode: Int = DEFAULT_HASH_MODE
    private var hashcatReady: Boolean = false
    @Volatile
    private var hashcatRunState: HashcatRunState = HashcatRunState.IDLE
    private var hashcatStatus: HashcatStatus = HashcatStatus()
    private var hashcatStartedAtRealtime: Long = 0L
    private var candidateSource: String = CANDIDATE_SOURCE_TEMPLATE
    private var maskTemplate: String = DEFAULT_MASK_TEMPLATE
    private var maskCharset: String = DEFAULT_MASK_CHARSET
    private var maskMinLength: Int = DEFAULT_MASK_MIN_LENGTH
    private var maskMaxLength: Int = DEFAULT_MASK_MAX_LENGTH
    private var selectedMemoryProfile: HashcatRunner.MemoryProfile = HashcatRunner.MemoryProfile.AUTO
    private var hashcatMemoryInfo: String? = null
    private var suppressMaskTextChange: Boolean = false

    private val hashcatLogLock = Any()
    private val hashcatLogHistory = ArrayDeque<String>()
    private val hashcatLiveStatus = linkedMapOf<String, String>()
    private val pendingHashcatLogLines = ArrayDeque<String>()
    private val hashcatLogHandler = Handler(Looper.getMainLooper())
    private var hashcatLogHistoryCharCount = 0
    private var hashcatLogFlushScheduled = false
    private val hashcatLogFlushRunnable = Runnable { flushHashcatLog() }

    /**
     * Applies the saved light or dark theme and application language before
     * Android inflates localized resources.
     */
    override fun attachBaseContext(newBase: Context)
    {
        val preferences = newBase.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val theme = preferences.getString(PREFERENCE_THEME, THEME_LIGHT) ?: THEME_LIGHT
        val language = preferences.getString(PREFERENCE_LANGUAGE, LANGUAGE_RUSSIAN)
            ?: LANGUAGE_RUSSIAN
        val configuration = Configuration(newBase.resources.configuration)
        val nightMode = if (theme == THEME_DARK)
        {
            Configuration.UI_MODE_NIGHT_YES
        }
        else
        {
            Configuration.UI_MODE_NIGHT_NO
        }
        val locale = Locale.forLanguageTag(language)

        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    /**
     * Inflates the persistent header and bottom navigation, restores selected
     * documents and connects Hashcat, converter, theme and language controls.
     */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestNotificationPermission()

        hashcatRunner = HashcatRunner(this)
        selectedMemoryProfile = loadMemoryProfile()
        binding.hashModeButton.isEnabled = false
        renderHashcatStatus()

        binding.themeToggleButton.setOnClickListener {
            toggleTheme()
        }

        binding.languageFlag.setOnClickListener {
            toggleLanguage()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId)
            {
                R.id.nav_hashcat,
                R.id.nav_converter,
                R.id.nav_log ->
                {
                    showScreen(item.itemId)
                    true
                }

                else -> false
            }
        }

        binding.selectHashFileButton.setOnClickListener {
            openDocument(REQUEST_OPEN_HASH_FILE)
        }

        binding.pasteHashButton.setOnClickListener {
            pasteHashesFromClipboard()
        }

        binding.hashModeButton.setOnClickListener {
            showHashModeDialog()
        }

        binding.candidateSourceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            candidateSource = if (checkedId == R.id.candidateWordlistRadioButton)
            {
                CANDIDATE_SOURCE_WORDLIST
            }
            else
            {
                CANDIDATE_SOURCE_TEMPLATE
            }
            saveCandidateSettings()
            renderCandidateSource()
            updateHashcatStartState()
        }

        setupMemoryProfileSpinner()

        binding.maskTemplateEditText.addTextChangedListener(object : TextWatcher
        {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            )
            {
            }

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            )
            {
            }

            override fun afterTextChanged(text: Editable?)
            {
                if (!suppressMaskTextChange)
                {
                    maskTemplate = text?.toString().orEmpty()
                    saveCandidateSettings()
                    renderCandidateInfo()
                    updateHashcatStartState()
                }
            }
        })

        binding.maskAssistantButton.setOnClickListener {
            showMaskAssistant()
        }

        binding.selectWordlistButton.setOnClickListener {
            openDocument(REQUEST_OPEN_WORDLIST)
        }

        binding.startHashcatButton.setOnClickListener {
            when (hashcatRunState)
            {
                HashcatRunState.IDLE -> startHashcat()
                HashcatRunState.STARTING,
                HashcatRunState.RUNNING -> requestHashcatStop("start/stop button")
                HashcatRunState.STOPPING -> Unit
            }
        }

        binding.selectCaptureButton.setOnClickListener {
            openDocument(REQUEST_OPEN_CAPTURE)
        }

        binding.convertButton.setOnClickListener {
            startConversion()
        }

        restorePersistentHashcatState()
        restoreUiState(savedInstanceState)
        renderHeaderControls()
        renderCandidateSource()
        renderMemoryProfile()
        renderHashcatMemoryInfo()
        showScreen(selectedScreenId)
        binding.bottomNav.selectedItemId = selectedScreenId
        updateHashcatStartState()
        prepareHashcatInBackground()
    }

    /**
     * Saves the active bottom-navigation screen and selected document sources so
     * theme, language and configuration recreation keeps the current workflow.
     */
    override fun onSaveInstanceState(outState: Bundle)
    {
        super.onSaveInstanceState(outState)

        outState.putInt(STATE_SELECTED_SCREEN, selectedScreenId)
        outState.putString(STATE_CAPTURE_URI, selectedCaptureUri?.toString())
        outState.putString(STATE_HASH_URI, selectedHashUri?.toString())
        outState.putString(STATE_WORDLIST_URI, selectedWordlistUri?.toString())
        outState.putString(
            STATE_CLIPBOARD_HASH_PATH,
            selectedHashClipboardFile?.absolutePath
        )
        outState.putInt(STATE_HASH_MODE, selectedHashMode)
        outState.putString(STATE_CANDIDATE_SOURCE, candidateSource)
        outState.putString(STATE_MASK_TEMPLATE, maskTemplate)
        outState.putString(STATE_MASK_CHARSET, maskCharset)
        outState.putInt(STATE_MASK_MIN_LENGTH, maskMinLength)
        outState.putInt(STATE_MASK_MAX_LENGTH, maskMaxLength)
        outState.putString(STATE_MEMORY_PROFILE, selectedMemoryProfile.name)
    }

    /**
     * Returns from secondary screens to the main Hashcat screen before
     * delegating the system Back action to Android.
     */
    override fun onBackPressed()
    {
        if (selectedScreenId != R.id.nav_hashcat)
        {
            binding.bottomNav.selectedItemId = R.id.nav_hashcat
            return
        }

        super.onBackPressed()
    }

    /**
     * Stops a native Hashcat process when this Activity is destroyed.
     */
    override fun onDestroy()
    {
        hashcatLogHandler.removeCallbacks(hashcatLogFlushRunnable)
        hashcatRunner.stop(
            "MainActivity.onDestroy finishing=$isFinishing " +
                "changingConfigurations=$isChangingConfigurations"
        )
        stopHashcatForegroundService()
        super.onDestroy()
    }

    /**
     * Receives Storage Access Framework results for capture selection, hc22000
     * output creation, Hashcat input selection and wordlist selection.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode)
        {
            REQUEST_OPEN_CAPTURE -> handleCaptureSelection(resultCode, data)
            REQUEST_CREATE_OUTPUT -> handleOutputSelection(resultCode, data)
            REQUEST_OPEN_HASH_FILE -> handleHashFileSelection(resultCode, data)
            REQUEST_OPEN_WORDLIST -> handleWordlistSelection(resultCode, data)
        }
    }

    /**
     * Requests notification access so the foreground Hashcat run is visible in
     * the notification shade on Android 13 and newer devices.
     */
    private fun requestNotificationPermission()
    {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU)
        {
            return
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        {
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    private fun toggleTheme()
    {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val currentTheme = preferences.getString(PREFERENCE_THEME, THEME_LIGHT) ?: THEME_LIGHT
        val nextTheme = if (currentTheme == THEME_DARK)
        {
            THEME_LIGHT
        }
        else
        {
            THEME_DARK
        }

        preferences.edit()
            .putString(PREFERENCE_THEME, nextTheme)
            .apply()

        recreate()
    }

    private fun toggleLanguage()
    {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val currentLanguage = preferences.getString(
            PREFERENCE_LANGUAGE,
            LANGUAGE_RUSSIAN
        ) ?: LANGUAGE_RUSSIAN
        val nextLanguage = if (currentLanguage == LANGUAGE_RUSSIAN)
        {
            LANGUAGE_ENGLISH
        }
        else
        {
            LANGUAGE_RUSSIAN
        }

        preferences.edit()
            .putString(PREFERENCE_LANGUAGE, nextLanguage)
            .apply()

        recreate()
    }

    private fun renderHeaderControls()
    {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val theme = preferences.getString(PREFERENCE_THEME, THEME_LIGHT) ?: THEME_LIGHT
        val language = preferences.getString(
            PREFERENCE_LANGUAGE,
            LANGUAGE_RUSSIAN
        ) ?: LANGUAGE_RUSSIAN

        if (theme == THEME_DARK)
        {
            binding.themeToggleButton.setImageResource(R.drawable.theme_moon)
            binding.themeToggleButton.contentDescription = getString(R.string.theme_dark)
        }
        else
        {
            binding.themeToggleButton.setImageResource(R.drawable.theme_sun)
            binding.themeToggleButton.contentDescription = getString(R.string.theme_light)
        }

        if (language == LANGUAGE_RUSSIAN)
        {
            binding.languageFlag.setImageResource(R.drawable.flag_ru)
            binding.languageFlag.contentDescription = getString(R.string.language_russian)
        }
        else
        {
            binding.languageFlag.setImageResource(R.drawable.flag_us)
            binding.languageFlag.contentDescription = getString(R.string.language_english)
        }
    }

    private fun showScreen(itemId: Int)
    {
        selectedScreenId = itemId
        binding.hashcatScreen.visibility = if (itemId == R.id.nav_hashcat)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }
        binding.converterScreen.visibility = if (itemId == R.id.nav_converter)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }
        binding.hashcatLogScreen.visibility = if (itemId == R.id.nav_log)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }
    }

    private fun restoreUiState(savedInstanceState: Bundle?)
    {
        if (savedInstanceState == null)
        {
            return
        }

        selectedScreenId = savedInstanceState.getInt(
            STATE_SELECTED_SCREEN,
            R.id.nav_hashcat
        )
        selectedCaptureUri = savedInstanceState.getString(STATE_CAPTURE_URI)?.let(Uri::parse)
        selectedHashUri = savedInstanceState.getString(STATE_HASH_URI)?.let(Uri::parse)
        selectedWordlistUri = savedInstanceState.getString(STATE_WORDLIST_URI)?.let(Uri::parse)
        selectedHashClipboardFile = savedInstanceState
            .getString(STATE_CLIPBOARD_HASH_PATH)
            ?.let(::File)
            ?.takeIf(File::isFile)
        selectedHashMode = savedInstanceState.getInt(
            STATE_HASH_MODE,
            DEFAULT_HASH_MODE
        )
        candidateSource = savedInstanceState.getString(
            STATE_CANDIDATE_SOURCE,
            CANDIDATE_SOURCE_TEMPLATE
        ) ?: CANDIDATE_SOURCE_TEMPLATE
        maskTemplate = savedInstanceState.getString(
            STATE_MASK_TEMPLATE,
            DEFAULT_MASK_TEMPLATE
        ) ?: DEFAULT_MASK_TEMPLATE
        maskCharset = savedInstanceState.getString(
            STATE_MASK_CHARSET,
            DEFAULT_MASK_CHARSET
        ) ?: DEFAULT_MASK_CHARSET
        maskMinLength = savedInstanceState.getInt(
            STATE_MASK_MIN_LENGTH,
            DEFAULT_MASK_MIN_LENGTH
        )
        maskMaxLength = savedInstanceState.getInt(
            STATE_MASK_MAX_LENGTH,
            DEFAULT_MASK_MAX_LENGTH
        )
        selectedMemoryProfile = parseMemoryProfile(
            savedInstanceState.getString(STATE_MEMORY_PROFILE)
        )

        selectedCaptureUri?.let { uri ->
            binding.selectedFileTextView.text = getString(
                R.string.selected_file,
                queryDisplayName(uri)
            )
            binding.convertButton.isEnabled = true
            describePcapUri(uri)
        }

        val clipboardFile = selectedHashClipboardFile
        if (clipboardFile != null)
        {
            binding.hashFileTextView.text = getString(
                R.string.clipboard_hashes_selected,
                clipboardFile.length()
            )
            describeHashFile(clipboardFile)
        }
        else
        {
            selectedHashUri?.let { uri ->
                binding.hashFileTextView.text = getString(
                    R.string.selected_hash_file,
                    queryDisplayName(uri)
                )
                describeHashUri(uri)
            }
        }

        selectedWordlistUri?.let { uri ->
            binding.wordlistFileTextView.text = getString(
                R.string.selected_wordlist,
                queryDisplayName(uri)
            )
            describeWordlistUri(uri)
        }
    }

    /**
     * Restores persisted Hashcat launch settings for a fresh Activity instance.
     * A saved wordlist is applied only while its document URI remains readable.
     */
    private fun restorePersistentHashcatState()
    {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        selectedHashMode = preferences.getInt(PREFERENCE_HASH_MODE, DEFAULT_HASH_MODE)
        candidateSource = preferences.getString(
            PREFERENCE_CANDIDATE_SOURCE,
            CANDIDATE_SOURCE_TEMPLATE
        ) ?: CANDIDATE_SOURCE_TEMPLATE
        maskTemplate = preferences.getString(
            PREFERENCE_MASK_TEMPLATE,
            DEFAULT_MASK_TEMPLATE
        ) ?: DEFAULT_MASK_TEMPLATE
        maskCharset = preferences.getString(
            PREFERENCE_MASK_CHARSET,
            DEFAULT_MASK_CHARSET
        ) ?: DEFAULT_MASK_CHARSET
        maskMinLength = preferences.getInt(
            PREFERENCE_MASK_MIN_LENGTH,
            DEFAULT_MASK_MIN_LENGTH
        )
        maskMaxLength = preferences.getInt(
            PREFERENCE_MASK_MAX_LENGTH,
            DEFAULT_MASK_MAX_LENGTH
        )
        selectedMemoryProfile = parseMemoryProfile(
            preferences.getString(PREFERENCE_MEMORY_PROFILE, null)
        )

        restorePersistentWordlist(preferences)
    }

    /**
     * Restores the saved wordlist URI when Android still grants read access to
     * the selected document.
     */
    private fun restorePersistentWordlist(preferences: android.content.SharedPreferences)
    {
        val uri = preferences.getString(PREFERENCE_WORDLIST_URI, null)
            ?.let(Uri::parse)
            ?.takeIf(::isReadableUri)

        if (uri == null)
        {
            selectedWordlistUri = null
            if (candidateSource == CANDIDATE_SOURCE_WORDLIST)
            {
                candidateSource = CANDIDATE_SOURCE_TEMPLATE
            }
            return
        }

        selectedWordlistUri = uri
        binding.wordlistFileTextView.text = getString(
            R.string.selected_wordlist,
            queryDisplayName(uri)
        )
        describeWordlistUri(uri)
    }

    /**
     * Persists the current hash mode for the next application launch.
     */
    private fun saveHashMode()
    {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFERENCE_HASH_MODE, selectedHashMode)
            .apply()
    }

    /**
     * Persists candidate-source, mask and wordlist settings for the next
     * application launch.
     */
    private fun saveCandidateSettings()
    {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_CANDIDATE_SOURCE, candidateSource)
            .putString(PREFERENCE_MASK_TEMPLATE, maskTemplate)
            .putString(PREFERENCE_MASK_CHARSET, maskCharset)
            .putInt(PREFERENCE_MASK_MIN_LENGTH, maskMinLength)
            .putInt(PREFERENCE_MASK_MAX_LENGTH, maskMaxLength)
            .putString(PREFERENCE_WORDLIST_URI, selectedWordlistUri?.toString())
            .apply()
    }

    /**
     * Returns true when a Storage Access Framework URI can still be opened for
     * reading by this app process.
     */
    private fun isReadableUri(uri: Uri): Boolean
    {
        return try
        {
            val input = contentResolver.openInputStream(uri) ?: return false
            input.close()
            true
        }
        catch (_: Throwable)
        {
            false
        }
    }

    private fun openDocument(requestCode: Int)
    {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        startActivityForResult(intent, requestCode)
    }

    private fun pasteHashesFromClipboard()
    {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isEmpty())
        {
            appendHashcatLog(getString(R.string.clipboard_empty))
            return
        }

        val inputDirectory = File(cacheDir, "hashcat-input").apply {
            mkdirs()
        }
        val clipboardFile = File(inputDirectory, "clipboard-hashes.txt")
        clipboardFile.writeText(text, Charsets.UTF_8)

        selectedHashUri = null
        selectedHashClipboardFile = clipboardFile
        binding.hashFileTextView.text = getString(
            R.string.clipboard_hashes_selected,
            text.length
        )
        describeHashFile(clipboardFile)
        appendHashcatLog(getString(R.string.clipboard_loaded))
        updateHashcatStartState()
    }

    private fun handleCaptureSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedCaptureUri = uri
        pendingConversion = null

        binding.selectedFileTextView.text = getString(
            R.string.selected_file,
            queryDisplayName(uri)
        )
        binding.convertButton.isEnabled = true
        binding.statusTextView.text = getString(R.string.status_ready)
        describePcapUri(uri)
    }

    private fun handleOutputSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            binding.statusTextView.text = getString(R.string.status_save_cancelled)
            return
        }

        val uri = data?.data ?: return
        writePendingConversion(uri)
    }

    private fun handleHashFileSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedHashClipboardFile?.delete()
        selectedHashClipboardFile = null
        selectedHashUri = uri
        binding.hashFileTextView.text = getString(
            R.string.selected_hash_file,
            queryDisplayName(uri)
        )
        describeHashUri(uri)

        updateHashcatStartState()
    }

    private fun handleWordlistSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedWordlistUri = uri
        candidateSource = CANDIDATE_SOURCE_WORDLIST
        saveCandidateSettings()
        binding.wordlistFileTextView.text = getString(
            R.string.selected_wordlist,
            queryDisplayName(uri)
        )
        describeWordlistUri(uri)
        renderCandidateSource()

        updateHashcatStartState()
    }

    private fun persistReadPermission(uri: Uri)
    {
        try
        {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        catch (_: SecurityException)
        {
        }
    }

    private fun startConversion()
    {
        val captureUri = selectedCaptureUri ?: return

        setConverterBusy(true)
        binding.statusTextView.text = getString(R.string.status_converting)

        thread(name = "cap-converter") {
            try
            {
                val result = contentResolver.openInputStream(captureUri)?.use { input ->
                    CapToHc22000Converter().convert(input)
                } ?: error(getString(R.string.error_open_capture))

                pendingConversion = result

                runOnUiThread {
                    setConverterBusy(false)

                    binding.statusTextView.text = getString(
                        R.string.status_conversion_result,
                        result.matchedPairCount,
                        result.lines.size,
                        result.skippedWithoutSsidCount,
                        result.packetCount
                    )

                    if (result.lines.isNotEmpty())
                    {
                        createOutputDocument(captureUri)
                    }
                }
            }
            catch (error: Exception)
            {
                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_error,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun createOutputDocument(captureUri: Uri)
    {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, suggestOutputName(captureUri))
        }

        startActivityForResult(intent, REQUEST_CREATE_OUTPUT)
    }

    private fun writePendingConversion(outputUri: Uri)
    {
        val result = pendingConversion ?: return

        setConverterBusy(true)
        binding.statusTextView.text = getString(R.string.status_saving)

        thread(name = "hc22000-writer") {
            try
            {
                contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        result.lines.forEach { line ->
                            writer.write(line)
                            writer.write("\n")
                        }
                    }
                } ?: error(getString(R.string.error_open_output))

                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_saved,
                        result.lines.size
                    )
                }
            }
            catch (error: Exception)
            {
                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_error,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun prepareHashcatInBackground()
    {
        hashcatReady = false
        binding.hashModeButton.isEnabled = false
        binding.hashModeTitleTextView.text = getString(R.string.hash_mode_loading)
        binding.hashModeCategoryTextView.text = ""
        binding.hashModeDescriptionTextView.text = ""
        binding.hashModeIconImageView.setImageResource(R.drawable.ic_hashcat)

        thread(name = "hashcat-runtime-prepare") {
            try
            {
                hashcatRunner.prepare()
                val loadedModes = hashcatRunner.loadHashModes()

                runOnUiThread {
                    if (isDestroyed)
                    {
                        return@runOnUiThread
                    }

                    hashModes = loadedModes
                    hashcatReady = true

                    if (hashModes.none { it.mode == selectedHashMode })
                    {
                        selectedHashMode = hashModes.first().mode
                        saveHashMode()
                    }

                    renderSelectedHashMode()
                    updateHashcatStartState()
                }
            }
            catch (error: Throwable)
            {
                runOnUiThread {
                    if (isDestroyed)
                    {
                        return@runOnUiThread
                    }

                    hashcatReady = false
                    binding.hashModeButton.isEnabled = false
                    binding.hashModeTitleTextView.text = getString(R.string.hash_mode_label)
                    binding.hashModeCategoryTextView.text = ""
                    binding.hashModeDescriptionTextView.text = ""
                    appendHashcatLog(
                        getString(
                            R.string.hash_mode_backend_error,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                    updateHashcatStartState()
                }
            }
        }
    }

    private fun showHashModeDialog()
    {
        if (!hashcatReady || hashModes.isEmpty())
        {
            return
        }

        HashModeDialog(
            activity = this,
            modes = hashModes,
            selectedMode = selectedHashMode
        ) { mode ->
            selectedHashMode = mode.mode
            saveHashMode()
            renderSelectedHashMode()
        }.show()
    }

    private fun renderSelectedHashMode()
    {
        val mode = hashModes.firstOrNull { it.mode == selectedHashMode } ?: return
        val presentation = HashModePresentation.categoryPresentation(mode.category)

        binding.hashModeIconImageView.setImageResource(presentation.iconRes)
        binding.hashModeTitleTextView.text = getString(
            R.string.hash_mode_selected,
            mode.mode,
            mode.name
        )
        binding.hashModeCategoryTextView.text = HashModePresentation.categoryTitle(
            this,
            mode.category
        )
        binding.hashModeDescriptionTextView.text = HashModePresentation.modeDescription(
            this,
            mode
        )
        binding.hashModeButton.isEnabled = !hashcatRunner.isRunning()
    }

    private fun showMaskAssistant()
    {
        MaskAssistantDialog(
            activity = this,
            currentMask = maskTemplate,
            currentCharset = maskCharset,
            currentMinLength = maskMinLength,
            currentMaxLength = maskMaxLength
        ) { settings ->
            candidateSource = CANDIDATE_SOURCE_TEMPLATE
            maskTemplate = settings.mask
            maskCharset = settings.charset
            maskMinLength = settings.minLength
            maskMaxLength = settings.maxLength
            saveCandidateSettings()
            renderCandidateSource()
            updateHashcatStartState()
        }.show()
    }

    private fun renderCandidateSource()
    {
        val useTemplate = candidateSource == CANDIDATE_SOURCE_TEMPLATE

        binding.candidateTemplateRadioButton.isChecked = useTemplate
        binding.candidateWordlistRadioButton.isChecked = !useTemplate
        binding.maskTemplateLabelTextView.visibility = if (useTemplate) View.VISIBLE else View.GONE
        binding.maskTemplateRow.visibility = if (useTemplate) View.VISIBLE else View.GONE
        binding.candidateTemplateInfoTextView.visibility = if (useTemplate) View.VISIBLE else View.GONE
        binding.wordlistFileTextView.visibility = if (useTemplate) View.GONE else View.VISIBLE
        binding.wordlistFileInfoTextView.visibility = if (useTemplate) View.GONE else View.VISIBLE
        binding.selectWordlistButton.visibility = if (useTemplate) View.GONE else View.VISIBLE
        binding.maskTemplateEditText.isEnabled = useTemplate && !hashcatRunner.isRunning()
        binding.maskAssistantButton.isEnabled = useTemplate && !hashcatRunner.isRunning()
        binding.selectWordlistButton.isEnabled = !useTemplate && !hashcatRunner.isRunning()

        if (binding.maskTemplateEditText.text.toString() != maskTemplate)
        {
            suppressMaskTextChange = true
            binding.maskTemplateEditText.setText(maskTemplate)
            suppressMaskTextChange = false
        }

        renderCandidateInfo()
    }

    private fun renderCandidateInfo()
    {
        binding.candidateTemplateInfoTextView.text = getString(
            R.string.mask_template_info,
            maskTemplate.ifBlank { getString(R.string.hashcat_status_unknown) },
            maskCharset.ifBlank { getString(R.string.hashcat_status_unknown) },
            maskMinLength,
            maskMaxLength
        )
    }

    private fun setupMemoryProfileSpinner()
    {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            memoryProfileLabels()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.memoryProfileSpinner.adapter = adapter
        binding.memoryProfileSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener
            {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                )
                {
                    val memoryProfile = memoryProfileAt(position)

                    if (memoryProfile != selectedMemoryProfile)
                    {
                        selectedMemoryProfile = memoryProfile
                        saveMemoryProfile(selectedMemoryProfile)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?)
                {
                }
            }
    }

    private fun renderMemoryProfile()
    {
        binding.memoryProfileSpinner.setSelection(
            memoryProfileIndex(selectedMemoryProfile),
            false
        )
    }

    private fun memoryProfileLabels(): List<String>
    {
        return listOf(
            getString(R.string.memory_profile_auto),
            getString(R.string.memory_profile_low),
            getString(R.string.memory_profile_balanced),
            getString(R.string.memory_profile_high)
        )
    }

    private fun memoryProfileAt(position: Int): HashcatRunner.MemoryProfile
    {
        return when (position)
        {
            1 -> HashcatRunner.MemoryProfile.LOW
            2 -> HashcatRunner.MemoryProfile.BALANCED
            3 -> HashcatRunner.MemoryProfile.HIGH
            else -> HashcatRunner.MemoryProfile.AUTO
        }
    }

    private fun memoryProfileIndex(memoryProfile: HashcatRunner.MemoryProfile): Int
    {
        return when (memoryProfile)
        {
            HashcatRunner.MemoryProfile.AUTO -> 0
            HashcatRunner.MemoryProfile.LOW -> 1
            HashcatRunner.MemoryProfile.BALANCED -> 2
            HashcatRunner.MemoryProfile.HIGH -> 3
        }
    }

    private fun loadMemoryProfile(): HashcatRunner.MemoryProfile
    {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        return parseMemoryProfile(preferences.getString(PREFERENCE_MEMORY_PROFILE, null))
    }

    private fun saveMemoryProfile(memoryProfile: HashcatRunner.MemoryProfile)
    {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_MEMORY_PROFILE, memoryProfile.name)
            .apply()
    }

    private fun parseMemoryProfile(value: String?): HashcatRunner.MemoryProfile
    {
        return HashcatRunner.MemoryProfile.values().firstOrNull { memoryProfile ->
            memoryProfile.name == value
        } ?: HashcatRunner.MemoryProfile.AUTO
    }

    private fun requestHashcatStop(reason: String)
    {
        if (hashcatRunState == HashcatRunState.STOPPING || hashcatRunState == HashcatRunState.IDLE)
        {
            return
        }

        hashcatRunState = HashcatRunState.STOPPING
        hashcatStatus = hashcatStatus.copy(
            state = getString(R.string.hashcat_status_stopping)
        )
        renderHashcatStatus()
        appendHashcatLog(getString(R.string.hashcat_stopping))
        binding.startHashcatButton.text = getString(R.string.hashcat_status_stopping)
        binding.startHashcatButton.isEnabled = false
        hashcatRunner.stop(reason)
    }

    private fun finishHashcatStop()
    {
        hashcatRunState = HashcatRunState.IDLE
        stopHashcatForegroundService()
        setHashcatBusy(false)
        hashcatStatus = hashcatStatus.copy(
            state = getString(R.string.hashcat_status_finished)
        )
        renderHashcatStatus()
    }

    private fun startHashcatForegroundService()
    {
        startForegroundService(
            Intent(this, HashcatForegroundService::class.java).setAction(
                HashcatForegroundService.ACTION_START
            )
        )
    }

    private fun stopHashcatForegroundService()
    {
        startService(
            Intent(this, HashcatForegroundService::class.java).setAction(
                HashcatForegroundService.ACTION_STOP
            )
        )
    }

    private fun startHashcat()
    {
        if (hashcatRunState != HashcatRunState.IDLE)
        {
            return
        }

        val hashUri = selectedHashUri
        val clipboardHashFile = selectedHashClipboardFile
        val hashMode = selectedHashMode

        if (hashUri == null && clipboardHashFile == null)
        {
            return
        }

        hashcatRunState = HashcatRunState.STARTING
        hashcatMemoryInfo = null
        renderHashcatMemoryInfo()
        clearHashcatLog()
        startHashcatForegroundService()
        hashcatStartedAtRealtime = SystemClock.elapsedRealtime()
        hashcatStatus = HashcatStatus(
            state = getString(R.string.hashcat_status_preparing)
        )
        renderHashcatStatus()
        setHashcatBusy(true)
        appendHashcatLog(getString(R.string.hashcat_preparing))

        thread(name = "hashcat-input-copy") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            try            {
                val inputDirectory = File(cacheDir, "hashcat-input").apply {
                    mkdirs()
                }
                val hashFile = prepareHashInputFile(
                    hashUri = hashUri,
                    clipboardHashFile = clipboardHashFile,
                    hashMode = hashMode,
                    inputDirectory = inputDirectory
                )
                val source = if (candidateSource == CANDIDATE_SOURCE_TEMPLATE)
                {
                    HashcatRunner.CandidateSource.Mask(
                        mask = maskTemplate.trim(),
                        customCharset = maskCharset.trim(),
                        incrementMin = maskMinLength,
                        incrementMax = maskMaxLength
                    )
                }
                else
                {
                    val wordlistUri = selectedWordlistUri
                        ?: error(getString(R.string.no_wordlist_selected))
                    HashcatRunner.CandidateSource.Wordlist(
                        copyUriToFile(
                            wordlistUri,
                            File(inputDirectory, "wordlist.input")
                        )
                    )
                }
                val recoveredFile = createRecoveredOutputFile(hashFile, hashUri)

                if (hashcatRunState == HashcatRunState.STOPPING)
                {
                    runOnUiThread {
                        finishHashcatStop()
                    }
                    return@thread
                }

                hashcatRunner.start(
                    hashFile = hashFile,
                    candidateSource = source,
                    hashMode = hashMode,
                    recoveredFile = recoveredFile,
                    memoryProfile = selectedMemoryProfile,
                    listener = createHashcatListener(recoveredFile)
                )
            }
            catch (error: Throwable)
            {
                runOnUiThread {
                    hashcatRunState = HashcatRunState.IDLE
                    stopHashcatForegroundService()
                    setHashcatBusy(false)
                    hashcatStatus = hashcatStatus.copy(
                        state = getString(R.string.hashcat_status_error)
                    )
                    renderHashcatStatus()
                    appendHashcatLog(
                        "ERROR: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * Resolves the actual text hash input passed to Hashcat. Classic capture
     * containers selected as hash input are converted explicitly for mode 22000
     * instead of being copied as binary data into hashes.input.
     */
    private fun prepareHashInputFile(
        hashUri: Uri?,
        clipboardHashFile: File?,
        hashMode: Int,
        inputDirectory: File
    ): File
    {
        clipboardHashFile?.takeIf(File::isFile)?.let { file ->
            return file
        }

        val uri = hashUri
            ?: error(getString(R.string.error_open_selected_file, "hash"))
        val sample = readSampleBytes(uri)
        val isCapture = sample.startsWith(0xD4, 0xC3, 0xB2, 0xA1) ||
            sample.startsWith(0xA1, 0xB2, 0xC3, 0xD4) ||
            sample.startsWith(0x4D, 0x3C, 0xB2, 0xA1) ||
            sample.startsWith(0xA1, 0xB2, 0x3C, 0x4D) ||
            sample.startsWith(0x0A, 0x0D, 0x0D, 0x0A)

        if (!isCapture)
        {
            return copyUriToFile(uri, File(inputDirectory, "hashes.input"))
        }

        if (hashMode != WPA_HASH_MODE)
        {
            error(getString(R.string.error_capture_requires_mode_22000))
        }

        val result = contentResolver.openInputStream(uri)?.use { input ->
            CapToHc22000Converter().convert(input)
        } ?: error(getString(R.string.error_open_capture))

        if (result.lines.isEmpty())
        {
            error(getString(R.string.error_capture_no_hc22000))
        }

        return File(inputDirectory, "hashes.hc22000").apply {
            bufferedWriter(Charsets.UTF_8).use { writer ->
                result.lines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }
        }
    }

    private fun createHashcatListener(recoveredFile: File): HashcatRunner.Listener
    {
        return object : HashcatRunner.Listener
        {
            override fun onStarted(command: List<String>)
            {
                runOnUiThread {
                    if (hashcatRunState == HashcatRunState.STOPPING)
                    {
                        hashcatRunner.stop("stop requested before native start callback")
                        return@runOnUiThread
                    }

                    appendHashcatLog(
                        "$ " + command.joinToString(" ")
                    )
                }
            }

            override fun onOutput(line: String)
            {
                if (hashcatRunState == HashcatRunState.STARTING)
                {
                    val startupState = when
                    {
                        line.contains("Initialized backend runtimes", ignoreCase = true) ->
                            getString(R.string.hashcat_status_backend_initialized)
                        line.contains("OpenCL API", ignoreCase = true) ->
                            getString(R.string.hashcat_status_opencl_initialized)
                        line.startsWith("Initializing device kernels and memory", ignoreCase = true) ->
                            getString(R.string.hashcat_status_kernels_memory)
                        line.startsWith("Starting autotune", ignoreCase = true) ->
                            getString(R.string.hashcat_status_autotune)
                        line.startsWith("Status") && line.contains("Running", ignoreCase = true) ->
                            getString(R.string.hashcat_status_running)
                        else -> null
                    }

                    if (startupState != null)
                    {
                        runOnUiThread {
                            if (line.startsWith("Status") && line.contains("Running", ignoreCase = true))
                            {
                                hashcatRunState = HashcatRunState.RUNNING
                            }

                            hashcatStatus = hashcatStatus.copy(state = startupState)
                            renderHashcatStatus()
                        }
                    }
                }

                appendHashcatLog(line)
            }

            override fun onFinished(exitCode: Int)
            {
                runOnUiThread {
                    hashcatRunState = HashcatRunState.IDLE
                    stopHashcatForegroundService()
                    setHashcatBusy(false)
                    hashcatStatus = hashcatStatus.copy(
                        state = getString(R.string.hashcat_status_finished)
                    )
                    renderHashcatStatus()
                    appendHashcatLog(
                        getString(R.string.hashcat_finished, exitCode)
                    )
                    if (exitCode == 0 && recoveredFile.isFile && recoveredFile.length() > 0L)
                    {
                        appendHashcatLog(
                            getString(
                                R.string.hashcat_result_saved,
                                recoveredFile.absolutePath
                            )
                        )
                    }
                }
            }

            override fun onError(error: Throwable)
            {
                runOnUiThread {
                    hashcatRunState = HashcatRunState.IDLE
                    stopHashcatForegroundService()
                    setHashcatBusy(false)
                    hashcatStatus = hashcatStatus.copy(
                        state = getString(R.string.hashcat_status_error)
                    )
                    renderHashcatStatus()
                    appendHashcatLog(
                        "ERROR: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * Stores the complete host-memory summary emitted by Hashcat for the current
     * attack. The UI presents Hashcat's own value instead of reconstructing it
     * from internal allocation diagnostics.
     */
    private fun updateHashcatMemoryInfoFromOutput(line: String): Boolean
    {
        val value = when
        {
            line.startsWith("Host memory allocated for this attack:", ignoreCase = true) ->
                line.trim()
            line.contains("Not enough allocatable device memory", ignoreCase = true) ->
                getString(R.string.hashcat_memory_info_not_enough)
            else -> return false
        }

        if (hashcatMemoryInfo == value)
        {
            return false
        }

        hashcatMemoryInfo = value

        return true
    }

    private fun renderHashcatMemoryInfo()
    {
        binding.hashcatMemoryInfoTextView.text =
            hashcatMemoryInfo ?: getString(R.string.hashcat_memory_info_empty)
    }

    private fun updateHashcatStatusFromOutput(line: String): Boolean
    {
        val label = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim()

        if (label.isEmpty() || value.isEmpty())
        {
            return false
        }

        val updated = when
        {
            label.startsWith("Status") -> hashcatStatus.copy(state = value)
            label.startsWith("Progress") -> hashcatStatus.copy(progress = value)
            label.startsWith("Recovered") -> hashcatStatus.copy(recovered = value)
            label.startsWith("Time.Started") -> hashcatStatus.copy(
                runtime = extractParenthesizedTail(value) ?: value
            )
            label.startsWith("Time.Estimated") -> hashcatStatus.copy(
                eta = extractParenthesizedTail(value) ?: value
            )
            label.startsWith("Speed.#") || label.startsWith("Speed.Dev") ->
                hashcatStatus.copy(speed = value)
            label.startsWith("Candidates.#") || label.startsWith("Candidate.Engine") ->
                hashcatStatus.copy(currentKey = value)
            else -> return false
        }

        hashcatStatus = updated

        return true
    }

    private fun extractParenthesizedTail(value: String): String?
    {
        val start = value.lastIndexOf('(')
        val end = value.lastIndexOf(')')

        return if (start >= 0 && end > start)
        {
            value.substring(start + 1, end)
        }
        else
        {
            null
        }
    }

    private fun renderHashcatStatus()
    {
        val fallback = getString(R.string.hashcat_status_unknown)
        val runtime = hashcatStatus.runtime ?: if (hashcatStartedAtRealtime > 0L)
        {
            formatDuration(SystemClock.elapsedRealtime() - hashcatStartedAtRealtime)
        }
        else
        {
            fallback
        }

        binding.hashcatStatusTextView.text = getString(
            R.string.hashcat_status_format,
            hashcatStatus.state ?: getString(R.string.hashcat_status_idle_value),
            hashcatStatus.progress ?: fallback,
            hashcatStatus.currentKey ?: fallback,
            runtime,
            hashcatStatus.eta ?: fallback,
            hashcatStatus.speed ?: fallback,
            hashcatStatus.recovered ?: fallback
        )
    }

    private fun formatDuration(milliseconds: Long): String
    {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0)
        {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        }
        else
        {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    private fun copyUriToFile(uri: Uri, destination: File): File
    {
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error(
            getString(
                R.string.error_open_selected_file,
                queryDisplayName(uri)
            )
        )

        return destination
    }

    private fun clearHashcatLog()
    {
        hashcatLogHandler.removeCallbacks(hashcatLogFlushRunnable)

        synchronized(hashcatLogLock)
        {
            hashcatLogHistory.clear()
            hashcatLiveStatus.clear()
            pendingHashcatLogLines.clear()
            hashcatLogHistoryCharCount = 0
            hashcatLogFlushScheduled = false
        }

        binding.hashcatLogTextView.setLogText("")
    }

    /**
     * Mirrors native output to Logcat immediately. The in-app view keeps startup
     * and event history immutable while Hashcat's repeating status block is
     * updated in place by field name. This avoids growing the TextView every
     * second and preserves the user's scroll position while they inspect history.
     */
    private fun appendHashcatLog(line: String)
    {
        android.util.Log.i("Hashcat", line)

        var scheduleFlush = false

        synchronized(hashcatLogLock)
        {
            pendingHashcatLogLines.addLast(line)

            if (!hashcatLogFlushScheduled)
            {
                hashcatLogFlushScheduled = true
                scheduleFlush = true
            }
        }

        if (scheduleFlush)
        {
            hashcatLogHandler.postDelayed(
                hashcatLogFlushRunnable,
                HASHCAT_LOG_FLUSH_DELAY_MS
            )
        }
    }

    private fun flushHashcatLog()
    {
        val pending = ArrayList<String>()

        synchronized(hashcatLogLock)
        {
            while (pendingHashcatLogLines.isNotEmpty())
            {
                pending += pendingHashcatLogLines.removeFirst()
            }

            hashcatLogFlushScheduled = false
        }

        if (pending.isEmpty())
        {
            return
        }

        var statusChanged = false
        var memoryChanged = false

        pending.forEach { line ->
            if (updateHashcatStatusFromOutput(line))
            {
                statusChanged = true
            }
            if (updateHashcatMemoryInfoFromOutput(line))
            {
                memoryChanged = true
            }

            val statusKey = hashcatLiveStatusKey(line)

            when
            {
                statusKey != null -> hashcatLiveStatus[statusKey] = line
                isHashcatStatusPrompt(line) -> Unit
                line.isBlank() && hashcatLiveStatus.isNotEmpty() -> Unit
                else -> appendHashcatHistoryLine(line)
            }
        }

        if (statusChanged)
        {
            renderHashcatStatus()
        }
        if (memoryChanged)
        {
            renderHashcatMemoryInfo()
        }

        trimHashcatHistory()

        val estimatedChars = hashcatLogHistoryCharCount +
            hashcatLiveStatus.values.sumOf { it.length + 1 } + 2

        binding.hashcatLogTextView.setLogText(buildString(estimatedChars) {
            hashcatLogHistory.forEach { line ->
                append(line)
                append('\n')
            }

            if (hashcatLiveStatus.isNotEmpty())
            {
                if (isNotEmpty() && this[lastIndex] != '\n') append('\n')
                append('\n')

                hashcatLiveStatus.values.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
        })

        var scheduleNextFlush = false

        synchronized(hashcatLogLock)
        {
            if (pendingHashcatLogLines.isNotEmpty() && !hashcatLogFlushScheduled)
            {
                hashcatLogFlushScheduled = true
                scheduleNextFlush = true
            }
        }

        if (scheduleNextFlush)
        {
            hashcatLogHandler.postDelayed(
                hashcatLogFlushRunnable,
                HASHCAT_LOG_FLUSH_DELAY_MS
            )
        }
    }

    private fun appendHashcatHistoryLine(line: String)
    {
        hashcatLogHistory.addLast(line)
        hashcatLogHistoryCharCount += line.length + 1
    }

    private fun trimHashcatHistory()
    {
        val liveLines = hashcatLiveStatus.size
        val maxHistoryLines = (MAX_HASHCAT_LOG_LINES - liveLines).coerceAtLeast(0)
        while (
            hashcatLogHistory.size > maxHistoryLines ||
            hashcatLogHistoryCharCount > MAX_HASHCAT_LOG_CHARS
        )
        {
            val removed = hashcatLogHistory.removeFirst()
            hashcatLogHistoryCharCount -= removed.length + 1
        }
    }

    private fun hashcatLiveStatusKey(line: String): String?
    {
        val rawLabel = line.substringBefore(':', "").trim()

        if (rawLabel.isEmpty()) return null

        val label = rawLabel.trimEnd('.')

        val live = label == "Session" ||
            label == "Status" ||
            label == "Hash.Mode" ||
            label == "Hash.Target" ||
            label == "Time.Started" ||
            label == "Time.Estimated" ||
            label == "Kernel.Feature" ||
            label.startsWith("Guess.") ||
            label.startsWith("Speed.") ||
            label == "Recovered" ||
            label == "Progress" ||
            label == "Rejected" ||
            label.startsWith("Restore.") ||
            label == "Candidate.Engine" ||
            label.startsWith("Candidates.") ||
            label.startsWith("Hardware.Mon.")

        return if (live) label else null
    }

    private fun isHashcatStatusPrompt(line: String): Boolean
    {
        return line.startsWith("[s]tatus [p]ause")
    }

    private fun setConverterBusy(busy: Boolean)
    {
        binding.progressBar.visibility = if (busy)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }

        binding.selectCaptureButton.isEnabled = !busy
        binding.convertButton.isEnabled = !busy && selectedCaptureUri != null
    }

    private fun setHashcatBusy(busy: Boolean)
    {
        binding.hashcatProgressBar.visibility = if (busy)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }

        binding.hashModeButton.isEnabled = !busy && hashcatReady
        binding.selectHashFileButton.isEnabled = !busy
        binding.pasteHashButton.isEnabled = !busy
        binding.candidateTemplateRadioButton.isEnabled = !busy
        binding.candidateWordlistRadioButton.isEnabled = !busy
        binding.maskTemplateEditText.isEnabled = !busy && candidateSource == CANDIDATE_SOURCE_TEMPLATE
        binding.maskAssistantButton.isEnabled = !busy && candidateSource == CANDIDATE_SOURCE_TEMPLATE
        binding.selectWordlistButton.isEnabled = !busy && candidateSource == CANDIDATE_SOURCE_WORDLIST
        binding.memoryProfileSpinner.isEnabled = !busy
        binding.startHashcatButton.text = when (hashcatRunState)
        {
            HashcatRunState.STARTING,
            HashcatRunState.RUNNING -> getString(R.string.stop_hashcat)
            HashcatRunState.STOPPING -> getString(R.string.hashcat_status_stopping)
            HashcatRunState.IDLE -> getString(R.string.start_hashcat)
        }
        binding.startHashcatButton.isEnabled = hashcatRunState == HashcatRunState.STARTING ||
            hashcatRunState == HashcatRunState.RUNNING

        if (!busy)
        {
            updateHashcatStartState()
        }
    }

    private fun updateHashcatStartState()
    {
        val hasHashInput =
            selectedHashUri != null ||
            selectedHashClipboardFile?.isFile == true
        val hasCandidates = if (candidateSource == CANDIDATE_SOURCE_TEMPLATE)
        {
            maskTemplate.isNotBlank()
        }
        else
        {
            selectedWordlistUri != null
        }

        binding.startHashcatButton.text = when (hashcatRunState)
        {
            HashcatRunState.STARTING,
            HashcatRunState.RUNNING -> getString(R.string.stop_hashcat)
            HashcatRunState.STOPPING -> getString(R.string.hashcat_status_stopping)
            HashcatRunState.IDLE -> getString(R.string.start_hashcat)
        }
        binding.startHashcatButton.isEnabled = when (hashcatRunState)
        {
            HashcatRunState.STARTING,
            HashcatRunState.RUNNING -> true
            HashcatRunState.STOPPING -> false
            HashcatRunState.IDLE -> hasHashInput &&
                hasCandidates &&
                hashcatReady
        }
    }

    private fun createRecoveredOutputFile(
        hashFile: File,
        sourceUri: Uri?
    ): File
    {
        val baseName = sourceUri
            ?.let(::queryDisplayName)
            ?.let { name -> name.substringBeforeLast('.', name) }
            ?.takeIf(String::isNotBlank)
            ?: firstHashAsFileName(hashFile)
        val safeName = sanitizeResultFileName(baseName)
        val directory = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir,
            "hashcat-results"
        ).apply {
            mkdirs()
        }
        val output = File(directory, "$safeName.found.txt")

        if (output.isFile)
        {
            output.delete()
        }

        return output
    }

    private fun firstHashAsFileName(hashFile: File): String
    {
        return hashFile.bufferedReader().useLines { lines ->
            lines.firstOrNull { line ->
                line.isNotBlank()
            }
        }
            ?.trim()
            ?.take(80)
            ?.takeIf(String::isNotBlank)
            ?: "hashcat-result"
    }

    private fun sanitizeResultFileName(value: String): String
    {
        return value
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .take(96)
            .ifBlank { "hashcat-result" }
    }

    private fun describeHashUri(uri: Uri)
    {
        binding.hashFileInfoTextView.text = getString(R.string.file_info_loading)

        thread(name = "hash-file-info") {
            val info = try
            {
                buildHashFileInfo(
                    name = queryDisplayName(uri),
                    size = queryDocumentSize(uri),
                    bytes = readSampleBytes(uri)
                )
            }
            catch (error: Throwable)
            {
                getString(R.string.file_info_error, error.message ?: error.javaClass.simpleName)
            }

            runOnUiThread {
                binding.hashFileInfoTextView.text = info
            }
        }
    }

    private fun describeHashFile(file: File)
    {
        binding.hashFileInfoTextView.text = buildHashFileInfo(
            name = file.name,
            size = file.length(),
            bytes = file.inputStream().use { input ->
                readLimitedBytes(input, MAX_SAMPLE_BYTES)
            }
        )
    }

    private fun describeWordlistUri(uri: Uri)
    {
        binding.wordlistFileInfoTextView.text = getString(R.string.file_info_loading)

        thread(name = "wordlist-file-info") {
            val info = try
            {
                buildWordlistInfo(
                    name = queryDisplayName(uri),
                    size = queryDocumentSize(uri),
                    bytes = readSampleBytes(uri)
                )
            }
            catch (error: Throwable)
            {
                getString(R.string.file_info_error, error.message ?: error.javaClass.simpleName)
            }

            runOnUiThread {
                binding.wordlistFileInfoTextView.text = info
            }
        }
    }

    private fun describePcapUri(uri: Uri)
    {
        binding.pcapInfoTextView.text = getString(R.string.file_info_loading)

        thread(name = "pcap-file-info") {
            val info = try
            {
                buildPcapInfo(
                    name = queryDisplayName(uri),
                    size = queryDocumentSize(uri),
                    bytes = readSampleBytes(uri)
                )
            }
            catch (error: Throwable)
            {
                getString(R.string.file_info_error, error.message ?: error.javaClass.simpleName)
            }

            runOnUiThread {
                binding.pcapInfoTextView.text = info
            }
        }
    }

    private fun buildHashFileInfo(
        name: String,
        size: Long?,
        bytes: ByteArray
    ): String
    {
        val text = bytes.toString(Charsets.UTF_8)
        val nonEmptyLines = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(2000)
            .toList()
        val firstLine = nonEmptyLines.firstOrNull().orEmpty()
        val probableType = when
        {
            firstLine.startsWith("WPA*02*") -> getString(R.string.file_info_type_wpa22000)
            firstLine.contains(':') -> getString(R.string.file_info_type_colon_hash)
            firstLine.isNotBlank() -> getString(R.string.file_info_type_text_hash)
            else -> getString(R.string.hashcat_status_unknown)
        }

        return getString(
            R.string.hash_file_info_format,
            name,
            formatFileSize(size),
            probableType,
            nonEmptyLines.size,
            firstLine.length
        )
    }

    private fun buildWordlistInfo(
        name: String,
        size: Long?,
        bytes: ByteArray
    ): String
    {
        val text = bytes.toString(Charsets.UTF_8)
        val words = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(2000)
            .toList()
        val firstWord = words.firstOrNull().orEmpty()
        val maxLength = words.maxOfOrNull(String::length) ?: 0

        return getString(
            R.string.wordlist_file_info_format,
            name,
            formatFileSize(size),
            words.size,
            firstWord.length,
            maxLength
        )
    }

    private fun buildPcapInfo(
        name: String,
        size: Long?,
        bytes: ByteArray
    ): String
    {
        val magic = bytes.take(4).joinToString(" ") { byte ->
            "%02X".format(Locale.ROOT, byte.toInt() and 0xFF)
        }
        val format = when
        {
            bytes.startsWith(0xD4, 0xC3, 0xB2, 0xA1) -> getString(R.string.pcap_format_little)
            bytes.startsWith(0xA1, 0xB2, 0xC3, 0xD4) -> getString(R.string.pcap_format_big)
            bytes.startsWith(0x0A, 0x0D, 0x0D, 0x0A) -> getString(R.string.pcap_format_pcapng)
            else -> getString(R.string.pcap_format_unknown)
        }
        val linkType = if (bytes.size >= 24 && format != getString(R.string.pcap_format_pcapng))
        {
            readLittleEndianInt(bytes, 20).toString()
        }
        else
        {
            getString(R.string.hashcat_status_unknown)
        }
        val packetEstimate = if (size != null && size > 24)
        {
            ((size - 24) / 96L).coerceAtLeast(1L).toString()
        }
        else
        {
            getString(R.string.hashcat_status_unknown)
        }

        return getString(
            R.string.pcap_file_info_format,
            name,
            formatFileSize(size),
            format,
            linkType,
            packetEstimate,
            magic
        )
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean
    {
        if (size < values.size)
        {
            return false
        }

        return values.indices.all { index ->
            (this[index].toInt() and 0xFF) == values[index]
        }
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int
    {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readSampleBytes(uri: Uri): ByteArray
    {
        return contentResolver.openInputStream(uri)?.use { input ->
            readLimitedBytes(input, MAX_SAMPLE_BYTES)
        } ?: ByteArray(0)
    }

    private fun readLimitedBytes(input: java.io.InputStream, limit: Int): ByteArray
    {
        val buffer = ByteArray(limit)
        val count = input.read(buffer)

        return if (count > 0)
        {
            buffer.copyOf(count)
        }
        else
        {
            ByteArray(0)
        }
    }

    private fun queryDocumentSize(uri: Uri): Long?
    {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex))
            {
                return cursor.getLong(sizeIndex)
            }
        }

        return null
    }

    private fun formatFileSize(size: Long?): String
    {
        if (size == null)
        {
            return getString(R.string.hashcat_status_unknown)
        }

        val units = arrayOf("B", "KB", "MB", "GB")
        var value = size.toDouble()
        var unit = 0

        while (value >= 1024.0 && unit < units.lastIndex)
        {
            value /= 1024.0
            unit++
        }

        return if (unit == 0)
        {
            "%d %s".format(Locale.ROOT, size, units[unit])
        }
        else
        {
            "%.1f %s".format(Locale.ROOT, value, units[unit])
        }
    }

    private fun queryDisplayName(uri: Uri): String
    {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (nameIndex >= 0 && cursor.moveToFirst())
            {
                return cursor.getString(nameIndex)
            }
        }

        return uri.lastPathSegment ?: getString(R.string.unknown_file_name)
    }

    private fun suggestOutputName(uri: Uri): String
    {
        val inputName = queryDisplayName(uri)
        val baseName = inputName.substringBeforeLast('.', inputName)

        return "$baseName.hc22000"
    }

    private companion object
    {
        const val REQUEST_OPEN_CAPTURE = 1001
        const val REQUEST_CREATE_OUTPUT = 1002
        const val REQUEST_OPEN_HASH_FILE = 1003
        const val REQUEST_OPEN_WORDLIST = 1004
        const val REQUEST_POST_NOTIFICATIONS = 1005

        const val PREFERENCES_NAME = "hashcat_ui"
        const val PREFERENCE_THEME = "theme"
        const val PREFERENCE_LANGUAGE = "language"
        const val PREFERENCE_MEMORY_PROFILE = "memory_profile"
        const val PREFERENCE_HASH_MODE = "hash_mode"
        const val PREFERENCE_CANDIDATE_SOURCE = "candidate_source"
        const val PREFERENCE_MASK_TEMPLATE = "mask_template"
        const val PREFERENCE_MASK_CHARSET = "mask_charset"
        const val PREFERENCE_MASK_MIN_LENGTH = "mask_min_length"
        const val PREFERENCE_MASK_MAX_LENGTH = "mask_max_length"
        const val PREFERENCE_WORDLIST_URI = "wordlist_uri"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANGUAGE_RUSSIAN = "ru"
        const val LANGUAGE_ENGLISH = "en"

        const val STATE_SELECTED_SCREEN = "selected_screen"
        const val STATE_CAPTURE_URI = "capture_uri"
        const val STATE_HASH_URI = "hash_uri"
        const val STATE_WORDLIST_URI = "wordlist_uri"
        const val STATE_CLIPBOARD_HASH_PATH = "clipboard_hash_path"
        const val STATE_HASH_MODE = "hash_mode"
        const val STATE_CANDIDATE_SOURCE = "candidate_source"
        const val STATE_MASK_TEMPLATE = "mask_template"
        const val STATE_MASK_CHARSET = "mask_charset"
        const val STATE_MASK_MIN_LENGTH = "mask_min_length"
        const val STATE_MASK_MAX_LENGTH = "mask_max_length"
        const val STATE_MEMORY_PROFILE = "memory_profile"

        const val CANDIDATE_SOURCE_TEMPLATE = "template"
        const val CANDIDATE_SOURCE_WORDLIST = "wordlist"
        const val WPA_HASH_MODE = 22000
        const val DEFAULT_HASH_MODE = WPA_HASH_MODE
        const val DEFAULT_MASK_TEMPLATE = "?1?1?1?1?1?1?1?1"
        const val DEFAULT_MASK_CHARSET = "?l?d"
        const val DEFAULT_MASK_MIN_LENGTH = 8
        const val DEFAULT_MASK_MAX_LENGTH = 8
        const val MAX_SAMPLE_BYTES = 65536

        const val MAX_HASHCAT_LOG_LINES = 300
        const val MAX_HASHCAT_LOG_CHARS = 128 * 1024
        const val HASHCAT_LOG_FLUSH_DELAY_MS = 750L
    }
}
