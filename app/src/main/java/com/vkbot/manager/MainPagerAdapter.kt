package com.vkbot.manager

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    
    override fun getItemCount(): Int = 4
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MainFragment()
            1 -> LogsFragment()
            2 -> StatisticsFragment()
            3 -> AnswersEditorFragment()
            else -> MainFragment()
        }
    }
}