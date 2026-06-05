package com.pushengage.PushNotificationDemo

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.model.request.Goal

class GoalActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var countInput: EditText
    private lateinit var valueInput: EditText
    private lateinit var sendGoalButton: MaterialButton
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goal)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        nameInput = findViewById(R.id.goalNameInput)
        countInput = findViewById(R.id.goalCountInput)
        valueInput = findViewById(R.id.goalValueInput)
        sendGoalButton = findViewById(R.id.sendGoalButton)
        progressBar = findViewById(R.id.progressBar)

        sendGoalButton.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            sendGoalButton.isEnabled = false
            this.didClickSendGoal()
        }
    }

    private fun didClickSendGoal() {
        var count: Int? = null
        var value: Double? = null
        countInput.text.toString().let {
            try {
                count = Integer.parseInt(countInput.text.toString())
            } catch(e: Exception) { }
        }
        valueInput.text.toString().let {9
            try {
                value = valueInput.text.toString().toDouble()
            } catch(e: Exception) { }
        }

        val goal = Goal(nameInput.text.toString(), count,  value)
        PushEngage.sendGoal(goal, loggingCallback(this, "sendGoal",
            onSuccess = {
                progressBar.visibility = View.GONE
                sendGoalButton.isEnabled = true
            },
            onFailure = { _, _ ->
                progressBar.visibility = View.GONE
                sendGoalButton.isEnabled = true
            }
        ))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            android.R.id.home -> {
                onBackPressed();
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        this.finish()
    }
}