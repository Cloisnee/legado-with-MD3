package io.legado.app.ui.book.info.edit

import android.os.Bundle
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class BookInfoEditActivity : BaseComposeActivity() {

    private val viewModel by viewModel<BookInfoEditViewModel>()

    @Composable
    override fun Content() {
        BookInfoEditScreen(
            viewModel = viewModel,
            onBack = { finish() },
            onSave = {
                viewModel.save {
                    setResult(RESULT_OK)
                    finish()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("bookUrl")?.let {
            viewModel.loadBook(it)
        }
    }

}
