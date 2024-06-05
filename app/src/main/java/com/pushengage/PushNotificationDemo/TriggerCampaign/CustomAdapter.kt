package com.pushengage.PushNotificationDemo.TriggerCampaign

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.pushengage.PushNotificationDemo.R

enum class DataCellState {
    add, cancel
}

class CustomAdapter(private val context: Context, private var data: ArrayList<CustomData>): RecyclerView.Adapter<CustomAdapter.ModelViewHolder>() {
    class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var key: EditText = itemView.findViewById(R.id.keyEditText)
        val value: EditText = itemView.findViewById(R.id.valueEditText)
        val actionButton: Button = itemView.findViewById(R.id.actionButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.row_item, parent, false)
        return ModelViewHolder(view)
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val actionButton = holder.actionButton

        val cellData = data[position]

        if(position == 0) {
            actionButton.text = "Add"
            holder.key.isEnabled = true
            holder.value.isEnabled = true
            holder.key.imeOptions = EditorInfo.IME_ACTION_DONE
            holder.value.imeOptions = EditorInfo.IME_ACTION_DONE
        } else {
            actionButton.text = "Cancel"
            holder.key.hint = null
            holder.value.hint = null
            holder.key.isEnabled = false
            holder.value.isEnabled = false
            holder.key.setText(cellData.keyText)
            holder.value.setText(cellData.valueText)
        }

        actionButton.setOnClickListener {
            if(cellData.cellState == DataCellState.add) {
                val keyText = holder.key.text.toString()
                val valueText = holder.value.text.toString()
                data.add(CustomData(keyText = keyText, valueText = valueText, DataCellState.cancel))
                holder.key.text.clear()
                holder.value.text.clear()
            } else {
                data.remove(cellData)
            }
            notifyDataSetChanged()
        }
    }
}
