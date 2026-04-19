package com.example.myapplication

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.graphics.Bitmap
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.data.Expense
import com.example.myapplication.data.ExpenseViewModel
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.databinding.DialogAddExpenseBinding
import com.example.myapplication.databinding.DialogExpenseDetailsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val expenseViewModel: ExpenseViewModel by viewModels()
    private val adapter = ExpenseAdapter()
    private val categories = arrayOf("Food", "Transport", "Shopping", "Entertainment", "Utilities", "Other")
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermission()

        setupRecyclerView()
        observeViewModel()

        binding.fabAdd.setOnClickListener {
            showAddExpenseDialog(null)
        }
        
        adapter.setOnClickListener { expense ->
            showExpenseDetailsDialog(expense)
        }

        adapter.setOnLongClickListener { expense ->
            showDeleteConfirmation(expense)
        }
    }

    private fun showExpenseDetailsDialog(expense: Expense) {
        val detailBinding = DialogExpenseDetailsBinding.inflate(layoutInflater)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        detailBinding.tvDetailTitle.text = expense.title
        detailBinding.tvDetailAmount.text = String.format("₹%.2f", expense.amount)
        detailBinding.tvDetailCategory.text = expense.category
        detailBinding.tvDetailDate.text = dateFormat.format(Date(expense.date))
        detailBinding.tvDetailLocation.text = expense.location ?: "No location saved"

        AlertDialog.Builder(this)
            .setView(detailBinding.root)
            .setPositiveButton("Edit") { _, _ ->
                showAddExpenseDialog(expense)
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete") { _, _ ->
                showDeleteConfirmation(expense)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_report_weekly -> {
                showReport("Weekly")
                true
            }
            R.id.action_report_monthly -> {
                showReport("Monthly")
                true
            }
            R.id.action_export -> {
                exportData()
                true
            }
            R.id.action_import -> {
                importData()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { saveFile(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readFile(it) }
    }

    private fun exportData() {
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        exportLauncher.launch("expenses_backup_$date.json")
    }

    private fun importData() {
        importLauncher.launch("application/json")
    }

    private fun saveFile(uri: Uri) {
        val expenses = expenseViewModel.allExpenses.value ?: return
        val json = Gson().toJson(expenses)
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray())
            }
            Toast.makeText(this, "Data exported successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val json = reader.readText()
                val type = object : TypeToken<List<Expense>>() {}.type
                val importedExpenses: List<Expense> = Gson().fromJson(json, type)
                
                importedExpenses.forEach { expense ->
                    // Insert as new (Room will handle IDs)
                    expenseViewModel.insert(expense.copy(id = 0))
                }
                Toast.makeText(this, "Imported ${importedExpenses.size} expenses", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReport(type: String) {
        val expenses = expenseViewModel.allExpenses.value ?: return
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        val filteredExpenses = if (type == "Weekly") {
            calendar.timeInMillis = now
            calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val weekStart = calendar.timeInMillis
            expenses.filter { it.date >= weekStart }
        } else {
            calendar.timeInMillis = now
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val monthStart = calendar.timeInMillis
            expenses.filter { it.date >= monthStart }
        }

        val total = filteredExpenses.sumOf { it.amount }
        val categoryTotals = filteredExpenses.groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val reportMessage = StringBuilder()
        reportMessage.append("Total: ₹%.2f\n\n".format(total))
        reportMessage.append("By Category:\n")
        categoryTotals.forEach { (category, amount) ->
            reportMessage.append("- $category: ₹%.2f\n".format(amount))
        }

        AlertDialog.Builder(this)
            .setTitle("$type Expense Report")
            .setMessage(if (filteredExpenses.isEmpty()) "No expenses found for this period." else reportMessage.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestLocationPermission() {
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                fetchLocation()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            fetchLocation()
        }
    }

    private fun fetchLocation(dialogBinding: DialogAddExpenseBinding? = null) {
        try {
            val priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val cts = CancellationTokenSource()
            
            fusedLocationClient.getCurrentLocation(priority, cts.token).addOnSuccessListener { location ->
                location?.let {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                    if (addresses?.isNotEmpty() == true) {
                        val addressLine = addresses[0].getAddressLine(0)
                        currentLocation = addressLine
                        dialogBinding?.etLocation?.setText(addressLine)
                    }
                }
            }
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val image = InputImage.fromFilePath(this, it)
            processImage(image)
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            val image = InputImage.fromBitmap(it, 0)
            processImage(image)
        }
    }

    private fun processImage(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank()) {
                    Toast.makeText(this, "No text found in image", Toast.LENGTH_SHORT).show()
                } else {
                    extractDataFromText(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun extractDataFromText(text: String) {
        val lines = text.split("\n")
        val fullTextLower = text.lowercase()
        
        // 1. Extract Store Name
        val storeName = lines.find { it.any { char -> char.isLetter() } }?.trim() ?: "Scanned Bill"

        // 2. Extract Amount
        val decimalRegex = Regex("""\d+[.,]\d{2}""")
        val decimalMatches = decimalRegex.findAll(text)
        var amount = decimalMatches.map { it.value.replace(",", ".").toDoubleOrNull() }
            .filterNotNull()
            .maxOrNull()

        if (amount == null) {
            val intRegex = Regex("""\d+""")
            amount = intRegex.findAll(text)
                .map { it.value.toDoubleOrNull() }
                .filterNotNull()
                .filter { it < 100000 }
                .maxOrNull()
        }

        // 3. Extract Date and Time
        val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""")
        val timeRegex = Regex("""(\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|am|pm)?)""")
        
        val dateMatch = dateRegex.find(text)?.value
        val timeMatch = timeRegex.find(text)?.value
        
        val dateLong = try {
            val calendar = Calendar.getInstance()
            
            dateMatch?.let {
                val cleanDate = it.replace("-", "/")
                val parts = cleanDate.split("/")
                val day = parts[0].toInt()
                val month = parts[1].toInt() - 1 // Calendar months are 0-based
                val yearStr = parts[2]
                val year = if (yearStr.length == 2) 2000 + yearStr.toInt() else yearStr.toInt()
                calendar.set(year, month, day)
            }
            
            timeMatch?.let {
                val isPM = it.lowercase().contains("pm")
                val isAM = it.lowercase().contains("am")
                val cleanTime = it.replace("AM", "", true).replace("PM", "", true).trim()
                val timeParts = cleanTime.split(":")
                var hour = timeParts[0].toInt()
                val minute = timeParts[1].toInt()
                
                if (isPM && hour < 12) hour += 12
                if (isAM && hour == 12) hour = 0
                
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
            }
            
            calendar.timeInMillis
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        // 4. Extract Category (Basic Keyword Mapping)
        var detectedCategory = "Other"
        val categoryKeywords = mapOf(
            "Food" to listOf("restaurant", "cafe", "food", "grocery", "market", "pizza", "burger", "dinner", "lunch", "breakfast", "eat"),
            "Transport" to listOf("uber", "ola", "taxi", "fuel", "petrol", "diesel", "metro", "train", "bus", "flight"),
            "Shopping" to listOf("mall", "store", "amazon", "flipkart", "clothing", "fashion", "electronics"),
            "Entertainment" to listOf("movie", "cinema", "netflix", "theatre", "gaming", "club", "bar"),
            "Utilities" to listOf("bill", "electricity", "water", "gas", "recharge", "internet", "wifi")
        )

        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { fullTextLower.contains(it) }) {
                detectedCategory = category
                break
            }
        }

        showAddExpenseDialog(null, amount, storeName, dateLong, detectedCategory)
    }

    private fun showDeleteConfirmation(expense: Expense) {
        AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Are you sure you want to delete this expense?")
            .setPositiveButton("Delete") { _, _ ->
                expenseViewModel.delete(expense)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun observeViewModel() {
        expenseViewModel.allExpenses.observe(this) { expenses ->
            adapter.submitList(expenses)
        }

        expenseViewModel.totalExpenses.observe(this) { total ->
            binding.tvTotalAmount.text = String.format("₹%.2f", total ?: 0.0)
        }
    }

    private fun showAddExpenseDialog(
        existingExpense: Expense? = null, 
        scannedAmount: Double? = null,
        scannedTitle: String? = null,
        scannedDate: Long? = null,
        scannedCategory: String? = null
    ) {
        val dialogBinding = DialogAddExpenseBinding.inflate(layoutInflater)
        
        // Refresh location and pass binding to update field when found
        fetchLocation(dialogBinding)

        val categoryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        dialogBinding.etCategory.setAdapter(categoryAdapter)

        var expenseDate = System.currentTimeMillis()

        existingExpense?.let {
            dialogBinding.etTitle.setText(it.title)
            dialogBinding.etAmount.setText(it.amount.toString())
            dialogBinding.etCategory.setText(it.category, false)
            dialogBinding.etLocation.setText(it.location)
            expenseDate = it.date
        }
        
        scannedAmount?.let { dialogBinding.etAmount.setText(it.toString()) }
        scannedTitle?.let { dialogBinding.etTitle.setText(it) }
        scannedDate?.let { expenseDate = it }
        scannedCategory?.let { dialogBinding.etCategory.setText(it, false) }
        
        // If not editing, show current cached location if available
        if (existingExpense == null && currentLocation != null) {
            dialogBinding.etLocation.setText(currentLocation)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existingExpense == null) "Add Expense" else "Edit Expense")
            .setView(dialogBinding.root)
            .setNeutralButton("Scan Bill") { _, _ ->
                val options = arrayOf("Camera", "Gallery")
                AlertDialog.Builder(this)
                    .setTitle("Select Source")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> takePictureLauncher.launch()
                            1 -> pickImageLauncher.launch("image/*")
                        }
                    }
                    .show()
            }
            .setPositiveButton(if (existingExpense == null) "Add" else "Update") { _, _ ->
                val title = dialogBinding.etTitle.text.toString()
                val amount = dialogBinding.etAmount.text.toString().toDoubleOrNull()
                val category = dialogBinding.etCategory.text.toString()
                val location = dialogBinding.etLocation.text.toString()

                if (title.isNotEmpty() && amount != null && category.isNotEmpty()) {
                    if (existingExpense == null) {
                        val expense = Expense(
                            title = title,
                            amount = amount,
                            date = expenseDate,
                            category = category,
                            location = location
                        )
                        expenseViewModel.insert(expense)
                    } else {
                        val updatedExpense = existingExpense.copy(
                            title = title,
                            amount = amount,
                            category = category,
                            date = expenseDate,
                            location = location
                        )
                        expenseViewModel.update(updatedExpense)
                    }
                } else {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
