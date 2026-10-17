package ke.sokobei.app.support

import android.content.Context
import androidx.room.Room
import ke.sokobei.app.data.db.SokoBeiDatabase

fun inMemoryDb(context: Context): SokoBeiDatabase =
    Room.inMemoryDatabaseBuilder(context, SokoBeiDatabase::class.java).allowMainThreadQueries().build()
