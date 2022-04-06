package com.simplemobiletools.dialer.fragments

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.AttributeSet
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.CallConfirmationDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.MyContactsContentProvider
import com.simplemobiletools.commons.helpers.PERMISSION_READ_CONTACTS
import com.simplemobiletools.commons.helpers.SimpleContactsHelper
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.activities.SimpleActivity
import com.simplemobiletools.dialer.adapters.ContactsAdapter
import com.simplemobiletools.dialer.adapters.RecentCallsAdapter
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.interfaces.RefreshItemsListener
import com.simplemobiletools.dialer.models.RecentCall
import kotlinx.android.synthetic.main.fragment_cati.view.*
import kotlinx.android.synthetic.main.fragment_letters_layout.view.*
import kotlinx.android.synthetic.main.fragment_recents.view.*
import java.util.*

class CatiFragment (context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet), RefreshItemsListener {

    override fun setupFragment() {
        val placeholderResId = BluetoothAdapter.getDefaultAdapter().getName()

        cati_placeholder.text = placeholderResId
        //fragment_placeholder_2.beGone()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, adjustedPrimaryColor: Int) {
        cati_placeholder.setTextColor(textColor)

    }

    override fun refreshItems() {
    }

    override fun onSearchClosed() {
    }

    override fun onSearchQueryChanged(text: String) {
    }
}