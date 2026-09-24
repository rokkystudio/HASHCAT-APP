package fuck.system.hashcat

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Presents Hashcat modes as a category tree with local search. Search inside a
 * category is limited to that category; search from the root scans all modes.
 */
class HashModeDialog(
    private val activity: Activity,
    private val modes: List<HashcatRunner.HashMode>,
    private val selectedMode: Int,
    private val onSelected: (HashcatRunner.HashMode) -> Unit
)
{
    private data class Row(
        val category: String,
        val mode: HashcatRunner.HashMode? = null,
        val count: Int = 0
    )

    private val groupedModes: Map<String, List<HashcatRunner.HashMode>> =
        modes.groupBy { it.category }
            .toList()
            .sortedBy { (category, _) ->
                HashModePresentation.categoryOrder()
                    .indexOf(category)
                    .takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            .toMap(LinkedHashMap())

    private lateinit var dialog: Dialog
    private lateinit var backButton: ImageButton
    private lateinit var titleTextView: TextView
    private lateinit var subtitleTextView: TextView
    private lateinit var searchEditText: EditText
    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter

    private var activeCategory: String? = null
    private var rootFirstVisiblePosition: Int = 0
    private var rootFirstVisibleTop: Int = 0

    fun show()
    {
        dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_hash_mode)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        backButton = dialog.findViewById(R.id.hashModeBackButton)
        titleTextView = dialog.findViewById(R.id.hashModeDialogTitle)
        subtitleTextView = dialog.findViewById(R.id.hashModeDialogSubtitle)
        searchEditText = dialog.findViewById(R.id.hashModeSearchEditText)
        listView = dialog.findViewById(R.id.hashModeListView)
        val closeButton: Button = dialog.findViewById(R.id.hashModeCloseButton)
        val emptyTextView: TextView = dialog.findViewById(R.id.hashModeEmptyTextView)

        adapter = RowAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyTextView

        backButton.setOnClickListener {
            navigateBack()
        }
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP)
            {
                navigateBack()
                true
            }
            else
            {
                false
            }
        }

        searchEditText.addTextChangedListener(object : TextWatcher
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
                render()
            }

            override fun afterTextChanged(text: Editable?)
            {
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)

            if (row.mode != null)
            {
                onSelected(row.mode)
                dialog.dismiss()
            }
            else
            {
                rememberRootScrollPosition()
                activeCategory = row.category
                searchEditText.text.clear()
                render()
                listView.post {
                    listView.setSelection(0)
                }
            }
        }

        render()
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * The Back action walks up from category to root before closing the dialog.
     */
    private fun navigateBack()
    {
        if (activeCategory != null)
        {
            activeCategory = null
            searchEditText.text.clear()
            render()
            listView.post {
                listView.setSelectionFromTop(rootFirstVisiblePosition, rootFirstVisibleTop)
            }
        }
        else
        {
            dialog.dismiss()
        }
    }

    private fun rememberRootScrollPosition()
    {
        rootFirstVisiblePosition = listView.firstVisiblePosition
        rootFirstVisibleTop = listView.getChildAt(0)?.top ?: 0
    }

    private fun render()
    {
        if (!::adapter.isInitialized)
        {
            return
        }

        val query = searchEditText.text
            ?.toString()
            ?.trim()
            .orEmpty()
        val category = activeCategory

        if (category != null)
        {
            titleTextView.text = HashModePresentation.categoryTitle(activity, category)
            subtitleTextView.text = HashModePresentation.categoryHint(activity, category)
            backButton.contentDescription = activity.getString(R.string.hash_mode_back)
            backButton.tooltipText = activity.getString(R.string.hash_mode_back)
        }
        else
        {
            titleTextView.text = activity.getString(R.string.hash_mode_dialog_title)
            subtitleTextView.text = activity.getString(R.string.hash_mode_dialog_subtitle)
            backButton.contentDescription = activity.getString(R.string.hash_mode_close)
            backButton.tooltipText = activity.getString(R.string.hash_mode_close)
        }

        val rows = when
        {
            query.isNotEmpty() ->
            {
                val source = category?.let { groupedModes[it].orEmpty() } ?: modes

                source.filter { mode ->
                    HashModePresentation.modeMatchesQuery(activity, mode, query)
                }.map { mode ->
                    Row(
                        category = mode.category,
                        mode = mode
                    )
                }
            }

            category != null ->
            {
                groupedModes[category].orEmpty().map { mode ->
                    Row(
                        category = category,
                        mode = mode
                    )
                }
            }

            else ->
            {
                groupedModes.map { (group, groupModes) ->
                    Row(
                        category = group,
                        count = groupModes.size
                    )
                }
            }
        }

        adapter.setRows(rows)
    }

    private inner class RowAdapter : BaseAdapter()
    {
        private val rows = ArrayList<Row>()

        fun setRows(newRows: List<Row>)
        {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getCount(): Int
        {
            return rows.size
        }

        override fun getItem(position: Int): Row
        {
            return rows[position]
        }

        override fun getItemId(position: Int): Long
        {
            return rows[position].mode?.mode?.toLong()
                ?: rows[position].category.hashCode().toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View
        {
            val view = convertView ?: LayoutInflater.from(activity).inflate(
                R.layout.row_hash_mode,
                parent,
                false
            )
            val row = getItem(position)
            val presentation = HashModePresentation.categoryPresentation(row.category)
            val icon: ImageView = view.findViewById(R.id.hashModeIcon)
            val title: TextView = view.findViewById(R.id.hashModeRowTitle)
            val subtitle: TextView = view.findViewById(R.id.hashModeRowSubtitle)
            val categoryTitle = activity.getString(presentation.titleRes)
            val categoryHint = activity.getString(presentation.hintRes)
            val mode = row.mode

            icon.setImageResource(presentation.iconRes)
            icon.tooltipText = categoryHint

            if (mode == null)
            {
                title.text = categoryTitle
                val count = activity.resources.getQuantityString(
                    R.plurals.hash_mode_count,
                    row.count,
                    row.count
                )
                subtitle.text = "$count · $categoryHint"
            }
            else
            {
                val description = HashModePresentation.modeDescription(activity, mode)
                val selectedPrefix = if (mode.mode == selectedMode)
                {
                    activity.getString(R.string.hash_mode_selected_marker) + " · "
                }
                else
                {
                    ""
                }
                title.text = activity.getString(
                    R.string.hash_mode_selected,
                    mode.mode,
                    mode.name
                )
                subtitle.text = if (
                    activeCategory == null &&
                    searchEditText.text?.toString()?.trim()?.isNotEmpty() == true
                )
                {
                    "$selectedPrefix$description\n$categoryTitle"
                }
                else
                {
                    "$selectedPrefix$description"
                }
            }

            return view
        }
    }
}
