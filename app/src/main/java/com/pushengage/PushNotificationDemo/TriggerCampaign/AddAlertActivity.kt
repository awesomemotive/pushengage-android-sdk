package com.pushengage.PushNotificationDemo.TriggerCampaign

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pushengage.PushNotificationDemo.R
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.PushEngage.TriggerAlertAvailabilityType
import com.pushengage.pushengage.PushEngage.TriggerAlertType
import com.pushengage.pushengage.model.request.TriggerAlert
import java.util.Calendar
import java.util.Date

class AddAlertActivity : AppCompatActivity() {

    private lateinit var typeSpinner: Spinner
    private lateinit var availabilitySpinner: Spinner
    private lateinit var productId: TextInputEditText
    private lateinit var link: TextInputEditText
    private lateinit var price: TextInputEditText
    private lateinit var variantId: TextInputEditText
    private lateinit var alertPrice: TextInputEditText
    private lateinit var addAlertButton: Button
    private lateinit var profileId: TextInputEditText
    private lateinit var alertPriceLayout: TextInputLayout
    private lateinit var alertMrp: TextInputEditText
    private val typeOptions = arrayOf("Price Drop", "Inventory")
    private val availabilityOptions = arrayOf("Nil", "In Stock", "Out of Stock")
    private val dataList: ArrayList<CustomData> = ArrayList()
    private lateinit var adapter: CustomAdapter
    private var selectedType: TriggerAlertType = TriggerAlertType.priceDrop
    private var selectedAvailabilityType: TriggerAlertAvailabilityType? = null
    private lateinit var selectDateTimeButton: Button
    private var selectedDateTime: Date? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_alert)
        productId = findViewById(R.id.alertProductId)
        link = findViewById(R.id.alertLink)
        price = findViewById(R.id.alertPrice)
        variantId = findViewById(R.id.alertVariantId)
        alertPrice = findViewById(R.id.alertAlertPrice)
        addAlertButton = findViewById(R.id.button)
        profileId = findViewById(R.id.profileId)
        alertPriceLayout = findViewById(R.id.alertAlertPriceLayout)
        alertMrp = findViewById(R.id.alertMrp)
        selectDateTimeButton = findViewById(R.id.expirySelectionButton)

        selectDateTimeButton.setOnClickListener {
            showDateTimePicker()
        }

        addAlertButton.setOnClickListener {
            var dataMap: MutableMap<String, String> = mutableMapOf()
            dataList.forEach { dataMap[it.keyText] = it.valueText }
            val productIdString = productId.text.toString()
            if(productIdString.isNullOrBlank()) {
                Toast.makeText(this, "Product Id cannot be empty", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val linkString = link.text.toString()
            if(linkString.isNullOrBlank()) {
                Toast.makeText(this, "Link cannot be empty", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val priceString = price.text.toString()
            if(priceString.isNullOrBlank()) {
                Toast.makeText(this, "Price cannot be empty", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val triggerAlert = TriggerAlert(
                selectedType,
                productIdString,
                linkString,
                priceString.toDouble(),
                variantId.text?.toString().takeIf { it?.isNotEmpty() ?: false },
                selectedDateTime,
                if(alertPrice.text.toString().isEmpty() || (selectedType == TriggerAlertType.inventory)) null else alertPrice.text.toString().toDouble(),
                selectedAvailabilityType,
                profileId.text.toString().takeIf { it.isNotEmpty() },
                if (alertMrp.text.toString().isEmpty()) null else alertMrp.text.toString().toDouble(),
                if(dataMap.isEmpty()) null else dataMap)

            PushEngage.addAlert(triggerAlert, object : PushEngageResponseCallback {
                override fun onSuccess(responseObject: Any?) {
                    Toast.makeText(this@AddAlertActivity, "Add Alert Successfully", Toast.LENGTH_LONG).show()
                }

                override fun onFailure(errorCode: Int?, errorMessage: String?) {
                    Toast.makeText(this@AddAlertActivity, errorMessage, Toast.LENGTH_LONG).show()
                }

            })
        }
        setupSpinners()
        setupList()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Add Alert"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }

    private fun showDateTimePicker() {
        val calendar = Calendar.getInstance().apply {
            selectedDateTime?.let {
                time = it
            }
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance().apply {
                    selectedDateTime?.let {
                        time = it
                    }
                }
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                val hour = selectedCalendar.get(Calendar.HOUR_OF_DAY)
                val minute = selectedCalendar.get(Calendar.MINUTE)

                val timePickerDialog = TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        selectedCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        selectedCalendar.set(Calendar.MINUTE, minute)

                        selectedDateTime = selectedCalendar.time

                    },
                    hour,
                    minute,
                    true
                )
                timePickerDialog.show()
            },
            year,
            month,
            day
        )

        datePickerDialog.show()
    }

    private fun setupList() {
        dataList.add(CustomData("","", DataCellState.add))
        adapter = CustomAdapter(this, dataList)

        val recyclerView: RecyclerView = findViewById(R.id.alertRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSpinners() {
        typeSpinner = findViewById(R.id.typeSpinner)
        availabilitySpinner = findViewById(R.id.availabilitySpinner)
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeOptions)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = typeAdapter

        val availabilityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availabilityOptions)
        availabilityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        availabilitySpinner.adapter = availabilityAdapter

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                selectedType = if(position == 0) {
                    alertPriceLayout.visibility = View.VISIBLE
                    TriggerAlertType.priceDrop
                } else {
                    alertPriceLayout.visibility = View.GONE
                    TriggerAlertType.inventory
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }

        }

        availabilitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                when(position) {
                    0 -> selectedAvailabilityType = null
                    1 -> selectedAvailabilityType = TriggerAlertAvailabilityType.inStock
                    2 -> selectedAvailabilityType = TriggerAlertAvailabilityType.outOfStock
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            android.R.id.home -> {
                onBackPressed()
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