package com.retrovault.platform.android

import android.content.Context
import com.retrovault.dat.DatByteSource
import com.retrovault.domain.identity.StorageRef
import java.io.FileNotFoundException
import java.io.InputStream

/** Opens a user-selected DAT document for the streaming parser. */
class AndroidDatByteSource(private val context: Context) : DatByteSource {
    override fun open(ref: StorageRef): InputStream =
        context.contentResolver.openInputStream(ref.toUri())?.buffered()
            ?: throw FileNotFoundException("the provider returned no stream for ${ref.value}")
}
