package fuck.system.hashcat

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView

/**
 * Builds a Hashcat mask template from simple character-set toggles and a
 * minimum/maximum length pair. The generated mask remains editable on the main
 * screen after the dialog is closed.
 */
class MaskAssistantDialog(
    private val activity: Activity,
    private val currentMask: String,
    private val currentCharset: String,
    private val currentMinLength: Int,
    private val currentMaxLength: Int,
    private val onApplied: (MaskSettings) -> Unit
)
{
    data class MaskSettings(
        val mask: String,
        val charset: String,
        val minLength: Int,
        val maxLength: Int
    )

    private lateinit var lowerCaseCheckBox: CheckBox
    private lateinit var upperCaseCheckBox: CheckBox
    private lateinit var digitsCheckBox: CheckBox
    private lateinit var symbolsCheckBox: CheckBox
    private lateinit var manualCharsetEditText: EditText
    private lateinit var minSeekBar: SeekBar
    private lateinit var maxSeekBar: SeekBar
    private lateinit var minEditText: EditText
    private lateinit var maxEditText: EditText
    private lateinit var previewTextView: TextView

    private var suppressLengthChange = false

    fun show()
    {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_mask_assistant)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        lowerCaseCheckBox = dialog.findViewById(R.id.maskLowercaseCheckBox)
        upperCaseCheckBox = dialog.findViewById(R.id.maskUppercaseCheckBox)
        digitsCheckBox = dialog.findViewById(R.id.maskDigitsCheckBox)
        symbolsCheckBox = dialog.findViewById(R.id.maskSymbolsCheckBox)
        manualCharsetEditText = dialog.findViewById(R.id.maskManualCharsetEditText)
        minSeekBar = dialog.findViewById(R.id.maskMinSeekBar)
        maxSeekBar = dialog.findViewById(R.id.maskMaxSeekBar)
        minEditText = dialog.findViewById(R.id.maskMinEditText)
        maxEditText = dialog.findViewById(R.id.maskMaxEditText)
        previewTextView = dialog.findViewById(R.id.maskPreviewTextView)
        val cancelButton: Button = dialog.findViewById(R.id.maskCancelButton)
        val applyButton: Button = dialog.findViewById(R.id.maskApplyButton)

        lowerCaseCheckBox.isChecked = currentCharset.contains("?l") || currentCharset.isBlank()
        upperCaseCheckBox.isChecked = currentCharset.contains("?u")
        digitsCheckBox.isChecked = currentCharset.contains("?d") || currentCharset.isBlank()
        symbolsCheckBox.isChecked = currentCharset.contains("?s")
        manualCharsetEditText.setText(extractManualCharset(currentCharset))

        minSeekBar.max = MAX_MASK_LENGTH - MIN_MASK_LENGTH
        maxSeekBar.max = MAX_MASK_LENGTH - MIN_MASK_LENGTH
        setLengths(
            currentMinLength.coerceIn(MIN_MASK_LENGTH, MAX_MASK_LENGTH),
            currentMaxLength.coerceIn(MIN_MASK_LENGTH, MAX_MASK_LENGTH)
        )

        listOf(
            lowerCaseCheckBox,
            upperCaseCheckBox,
            digitsCheckBox,
            symbolsCheckBox
        ).forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }
        }
        manualCharsetEditText.addTextChangedListener(createPreviewWatcher())

        minSeekBar.setOnSeekBarChangeListener(createSeekBarListener {
            val min = seekBarLength(minSeekBar).coerceAtMost(seekBarLength(maxSeekBar))
            setLengths(min, seekBarLength(maxSeekBar))
        })
        maxSeekBar.setOnSeekBarChangeListener(createSeekBarListener {
            val max = seekBarLength(maxSeekBar).coerceAtLeast(seekBarLength(minSeekBar))
            setLengths(seekBarLength(minSeekBar), max)
        })
        minEditText.addTextChangedListener(createLengthWatcher {
            val min = lengthFromText(minEditText).coerceAtMost(lengthFromText(maxEditText))
            setLengths(min, lengthFromText(maxEditText))
        })
        maxEditText.addTextChangedListener(createLengthWatcher {
            val max = lengthFromText(maxEditText).coerceAtLeast(lengthFromText(minEditText))
            setLengths(lengthFromText(minEditText), max)
        })

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        applyButton.setOnClickListener {
            val settings = buildSettings()
            onApplied(settings)
            dialog.dismiss()
        }

        updatePreview()
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createSeekBarListener(onChanged: () -> Unit): SeekBar.OnSeekBarChangeListener
    {
        return object : SeekBar.OnSeekBarChangeListener
        {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            )
            {
                if (fromUser && !suppressLengthChange)
                {
                    onChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?)
            {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?)
            {
            }
        }
    }

    private fun createPreviewWatcher(): TextWatcher
    {
        return object : TextWatcher
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
                updatePreview()
            }
        }
    }

    private fun createLengthWatcher(onChanged: () -> Unit): TextWatcher
    {
        return object : TextWatcher
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
                if (!suppressLengthChange)
                {
                    onChanged()
                }
            }
        }
    }

    private fun setLengths(minLength: Int, maxLength: Int)
    {
        val normalizedMin = minLength.coerceIn(MIN_MASK_LENGTH, MAX_MASK_LENGTH)
        val normalizedMax = maxLength.coerceIn(normalizedMin, MAX_MASK_LENGTH)

        suppressLengthChange = true
        minSeekBar.progress = normalizedMin - MIN_MASK_LENGTH
        maxSeekBar.progress = normalizedMax - MIN_MASK_LENGTH
        minEditText.setText(normalizedMin.toString())
        maxEditText.setText(normalizedMax.toString())
        suppressLengthChange = false
        updatePreview()
    }

    private fun seekBarLength(seekBar: SeekBar): Int
    {
        return seekBar.progress + MIN_MASK_LENGTH
    }

    private fun lengthFromText(editText: EditText): Int
    {
        if (!::minEditText.isInitialized || !::maxEditText.isInitialized)
        {
            return currentMinLength.coerceIn(MIN_MASK_LENGTH, MAX_MASK_LENGTH)
        }

        return editText.text
            ?.toString()
            ?.toIntOrNull()
            ?.coerceIn(MIN_MASK_LENGTH, MAX_MASK_LENGTH)
            ?: MIN_MASK_LENGTH
    }

    private fun extractManualCharset(charset: String): String
    {
        return charset
            .replace("?l", "")
            .replace("?u", "")
            .replace("?d", "")
            .replace("?s", "")
    }

    private fun buildSettings(): MaskSettings
    {
        val minLength = lengthFromText(minEditText)
        val maxLength = if (::maxEditText.isInitialized)
        {
            lengthFromText(maxEditText).coerceAtLeast(minLength)
        }
        else
        {
            currentMaxLength.coerceAtLeast(minLength)
        }
        val charset = buildCharset()
        val mask = "?1".repeat(maxLength)

        return MaskSettings(
            mask = mask.ifBlank { currentMask },
            charset = charset,
            minLength = minLength,
            maxLength = maxLength
        )
    }

    private fun updatePreview()
    {
        if (!::previewTextView.isInitialized)
        {
            return
        }

        val settings = buildSettings()

        previewTextView.text = activity.getString(
            R.string.mask_assistant_preview,
            settings.mask,
            settings.charset,
            settings.minLength,
            settings.maxLength
        )
    }

    private fun buildCharset(): String
    {
        val builder = StringBuilder()

        if (!::lowerCaseCheckBox.isInitialized)
        {
            return currentCharset.ifBlank { "?l?d" }
        }

        if (lowerCaseCheckBox.isChecked)
        {
            builder.append("?l")
        }
        if (upperCaseCheckBox.isChecked)
        {
            builder.append("?u")
        }
        if (digitsCheckBox.isChecked)
        {
            builder.append("?d")
        }
        if (symbolsCheckBox.isChecked)
        {
            builder.append("?s")
        }

        if (::manualCharsetEditText.isInitialized)
        {
            builder.append(manualCharsetEditText.text?.toString().orEmpty())
        }

        return builder.toString().ifBlank { "?l?d" }
    }

    private companion object
    {
        const val MIN_MASK_LENGTH = 1
        const val MAX_MASK_LENGTH = 32
    }
}
