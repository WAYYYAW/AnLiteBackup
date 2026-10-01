package com.anlite.backup.data.entity

import androidx.compose.ui.graphics.vector.ImageVector
import com.anlite.backup.HELP_CHANGELOG
import com.anlite.backup.HELP_FAQ
import com.anlite.backup.HELP_ISSUES
import com.anlite.backup.HELP_LICENSE
import com.anlite.backup.HELP_MATRIX
import com.anlite.backup.HELP_TELEGRAM
import com.anlite.backup.R
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.ArrowsClockwise
import com.anlite.backup.ui.compose.icons.phosphor.BracketsSquare
import com.anlite.backup.ui.compose.icons.phosphor.CircleWavyQuestion
import com.anlite.backup.ui.compose.icons.phosphor.Info
import com.anlite.backup.ui.compose.icons.phosphor.TelegramLogo
import com.anlite.backup.ui.compose.icons.phosphor.Warning

data class Link(
    val nameId: Int,
    val icon: ImageVector,
    val iconColorId: Int,
    val uri: String,
) {

    companion object {
        val Changelog = Link(
            R.string.help_changelog,
            Phosphor.ArrowsClockwise,
            R.color.ic_updated,
            HELP_CHANGELOG
        )
        val Telegram = Link(
            R.string.help_group_telegram,
            Phosphor.TelegramLogo,
            R.color.ic_system,
            HELP_TELEGRAM
        )
        val Matrix = Link(
            R.string.help_group_matrix,
            Phosphor.BracketsSquare,
            R.color.ic_apk,
            HELP_MATRIX
        )
        val License = Link(
            R.string.help_license,
            Phosphor.Info,
            R.color.ic_ext_data,
            HELP_LICENSE
        )
        val Issues = Link(
            R.string.help_issues,
            Phosphor.Warning,
            R.color.ic_de_data,
            HELP_ISSUES
        )
        val FAQ = Link(
            R.string.help_faq,
            Phosphor.CircleWavyQuestion,
            R.color.ic_special,
            HELP_FAQ
        )
    }
}