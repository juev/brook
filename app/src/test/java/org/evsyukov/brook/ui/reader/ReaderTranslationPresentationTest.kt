package org.evsyukov.brook.ui.reader

import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.TranslationReply
import org.junit.Test

class ReaderTranslationPresentationTest {
    @Test fun displaysTranslatedTitleAndBodyButKeepsTheSourceAndOriginalUrl() {
        val entry = EntryEntity(1,"feed/1","Original","","https://example.org",1,"RSS","",true,false,1,fullContent="Full")
        val state = ReaderState(entry=entry,showFullText=true,translation=TranslationReply("1","ru",title="Перевод",content="Текст"))
        assertThat(state.displayTitle).isEqualTo("Перевод")
        assertThat(state.displayBody).isEqualTo("Текст")
        assertThat(state.entry).isEqualTo(entry)
        assertThat(state.copy(translation=null).displayBody).isEqualTo("Full")
        assertThat(state.copy(translation=null,showFullText=false).displayBody).isEqualTo("RSS")
    }
}
