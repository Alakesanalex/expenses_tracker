package com.example.myapplication

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.data.Expense
import com.example.myapplication.databinding.ExpenseItemBinding
import java.text.SimpleDateFormat
import java.util.*

class ExpenseAdapter : ListAdapter<Expense, ExpenseAdapter.ExpenseViewHolder>(ExpenseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val binding = ExpenseItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExpenseViewHolder(binding)
    }

    private var onLongClick: (Expense) -> Unit = {}
    private var onClick: (Expense) -> Unit = {}

    fun setOnLongClickListener(listener: (Expense) -> Unit) {
        onLongClick = listener
    }

    fun setOnClickListener(listener: (Expense) -> Unit) {
        onClick = listener
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(getItem(position), onClick, onLongClick)
    }

    class ExpenseViewHolder(private val binding: ExpenseItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun bind(expense: Expense, onClick: (Expense) -> Unit, onLongClick: (Expense) -> Unit) {
            binding.tvTitle.text = expense.title
            binding.tvCategory.text = expense.category
            binding.tvDate.text = dateFormat.format(Date(expense.date))
            binding.tvAmount.text = String.format("₹%.2f", expense.amount)

            binding.root.setOnClickListener { onClick(expense) }
            binding.root.setOnLongClickListener {
                onLongClick(expense)
                true
            }
        }
    }

    class ExpenseDiffCallback : DiffUtil.ItemCallback<Expense>() {
        override fun areItemsTheSame(oldItem: Expense, newItem: Expense): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Expense, newItem: Expense): Boolean {
            return oldItem == newItem
        }
    }
}
