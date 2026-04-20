package com.example.myapplication

import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.viewModels
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.data.Expense
import com.example.myapplication.data.ExpenseViewModel
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.databinding.DialogAddExpenseBinding
import com.example.myapplication.databinding.DialogExpenseDetailsBinding
import com.example.myapplication.util.DataParser
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
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val expenseViewModel: ExpenseViewModel by viewModels()
    private val adapter = ExpenseAdapter()
    private val expenseCategories = arrayOf("Food", "Transport", "Shopping", "Entertainment", "Utilities", "Rent", "Health", "Other")
    private val incomeCategories = arrayOf("Salary", "Business", "Investment", "Gift", "Freelance", "Other")
    private val dataParser = DataParser()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: String? = null
    private var photoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Keep the splash screen on-screen for a bit longer (e.g., 1.5 seconds)
        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }
        binding = ActivityMainBinding.inflate(layoutInflater).also {
            it.root.postDelayed({ keepSplashScreen = false }, 1500)
        }

        // Add exit animation for the splash screen
        splashScreen.setOnExitAnimationListener { splashScreenProvider ->
            val splashScreenView = splashScreenProvider.view
            
            // Create a fade out and scale up animation for the "slowly open" effect
            val fadeOut = android.view.animation.AlphaAnimation(1f, 0f)
            fadeOut.duration = 800
            
            val scaleUp = android.view.animation.ScaleAnimation(
                1f, 1.5f, 1f, 1.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            )
            scaleUp.duration = 800
            
            val animationSet = android.view.animation.AnimationSet(true)
            animationSet.addAnimation(fadeOut)
            animationSet.addAnimation(scaleUp)
            
            animationSet.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    splashScreenProvider.remove()
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            
            splashScreenView.startAnimation(animationSet)
        }

        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermission()

        setupRecyclerView()
        observeViewModel()

        binding.fabAdd.setOnClickListener {
            showAddExpenseDialog(null)
        }

        binding.btnScanDashboard.setOnClickListener {
            val options = arrayOf("Camera", "Gallery")
            AlertDialog.Builder(this)
                .setTitle("Select Source")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> startCamera()
                        1 -> pickImageLauncher.launch("image/*")
                    }
                }
                .show()
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
        
        val context = detailBinding.root.context
        if (expense.type == "INCOME") {
            detailBinding.tvDetailAmount.text = String.format("+ ₹%.2f", expense.amount)
            detailBinding.tvDetailAmount.setTextColor(ContextCompat.getColor(context, R.color.income_green))
        } else {
            detailBinding.tvDetailAmount.text = String.format("- ₹%.2f", expense.amount)
            detailBinding.tvDetailAmount.setTextColor(ContextCompat.getColor(context, R.color.expense_red))
        }

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
            R.id.action_export_excel -> {
                exportToExcel()
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

    private val exportExcelLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        uri?.let { saveExcelFile(it) }
    }

    private fun exportToExcel() {
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        exportExcelLauncher.launch("expenses_$date.xlsx")
    }

    private fun saveExcelFile(uri: Uri) {
        val expenses = expenseViewModel.allExpenses.value ?: return
        val csvHeader = "ID,Title,Amount,Category,Date,Location,Type\n"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val csvContent = StringBuilder(csvHeader)
        expenses.forEach { expense ->
            val escapedTitle = expense.title.replace("\"", "\"\"")
            val escapedCategory = expense.category.replace("\"", "\"\"")
            val escapedLocation = (expense.location ?: "").replace("\"", "\"\"")
            val line = "${expense.id},\"$escapedTitle\",${expense.amount},\"$escapedCategory\",\"${dateFormat.format(Date(expense.date))}\",\"$escapedLocation\",${expense.type}\n"
            csvContent.append(line)
        }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(csvContent.toString().toByteArray())
            }
            Toast.makeText(this, "Excel compatible file exported successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
            expenses.filter { it.date >= weekStart && it.type == "EXPENSE" }
        } else {
            calendar.timeInMillis = now
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val monthStart = calendar.timeInMillis
            expenses.filter { it.date >= monthStart && it.type == "EXPENSE" }
        }

        if (filteredExpenses.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("$type Expense Report")
                .setMessage("No expenses found for this period.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val total = filteredExpenses.sumOf { it.amount }
        val categoryTotals = filteredExpenses.groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val dialogView = layoutInflater.inflate(R.layout.dialog_report, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvReportTitle)
        val tvTotal = dialogView.findViewById<TextView>(R.id.tvReportTotal)
        val llBreakdown = dialogView.findViewById<LinearLayout>(R.id.llCategoryBreakdown)
        val btnOk = dialogView.findViewById<Button>(R.id.btnReportOk)

        tvTitle.text = "$type Expense Report"
        tvTotal.text = "₹%.2f".format(total)

        categoryTotals.forEach { (category, amount) ->
            val itemView = layoutInflater.inflate(R.layout.item_report_category, llBreakdown, false)
            itemView.findViewById<TextView>(R.id.tvCategoryName).text = category
            itemView.findViewById<TextView>(R.id.tvCategoryAmount).text = "₹%.2f".format(amount)
            llBreakdown.addView(itemView)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
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

    private fun startCamera() {
        val photoFile = File.createTempFile(
            "IMG_${System.currentTimeMillis()}_",
            ".jpg",
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        )
        photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        takePictureLauncher.launch(photoUri)
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val image = InputImage.fromFilePath(this, it)
                processImage(image)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            photoUri?.let { uri ->
                try {
                    val image = InputImage.fromFilePath(this, uri)
                    processImage(image)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun processImage(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank()) {
                    Toast.makeText(this, "Scanning failed: No text detected. Try cleaning the lens or better lighting.", Toast.LENGTH_LONG).show()
                } else {
                    // Log the full text to Logcat for debugging if it still fails
                    android.util.Log.d("OCR_TEXT", visionText.text)
                    extractDataFromText(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "ML Kit Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun extractDataFromText(text: String) {
        val scannedData = dataParser.parse(text)
        showAddExpenseDialog(
            null,
            scannedData.amount,
            scannedData.title,
            scannedData.date,
            scannedData.category
        )
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
            updateDashboard(expenses)
        }
    }

    private fun updateDashboard(expenses: List<Expense>) {
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        val thisMonthExpenses = expenses.filter {
            val date = Calendar.getInstance().apply { timeInMillis = it.date }
            date.get(Calendar.MONTH) == currentMonth && date.get(Calendar.YEAR) == currentYear
        }

        val totalIncome = thisMonthExpenses.filter { it.type == "INCOME" }.sumOf { it.amount }
        val totalExpense = thisMonthExpenses.filter { it.type == "EXPENSE" }.sumOf { it.amount }
        val balance = totalIncome - totalExpense

        binding.tvIncomeAmount.text = String.format("₹%.2f", totalIncome)
        binding.tvExpenseAmount.text = String.format("₹%.2f", totalExpense)
        binding.tvBalanceAmount.text = String.format("₹%.2f", balance)

        if (balance < 0) {
            binding.tvBalanceAmount.setTextColor(ContextCompat.getColor(this, R.color.expense_red))
        } else {
            binding.tvBalanceAmount.setTextColor(ContextCompat.getColor(this, R.color.income_green))
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
        
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, arrayOf("EXPENSE", "INCOME"))
        dialogBinding.etType.setAdapter(typeAdapter)

        fun updateCategoryAdapter(type: String) {
            val cats = if (type == "INCOME") incomeCategories else expenseCategories
            val categoryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cats)
            dialogBinding.etCategory.setAdapter(categoryAdapter)
            // Clear or reset category if it's not in the new list
            if (existingExpense == null && !cats.contains(dialogBinding.etCategory.text.toString())) {
                dialogBinding.etCategory.setText("", false)
            }
        }

        dialogBinding.etType.setOnItemClickListener { _, _, _, _ ->
            updateCategoryAdapter(dialogBinding.etType.text.toString())
        }

        // Refresh location and pass binding to update field when found
        fetchLocation(dialogBinding)

        var expenseDate = System.currentTimeMillis()

        existingExpense?.let {
            dialogBinding.etType.setText(it.type, false)
            updateCategoryAdapter(it.type)
            dialogBinding.etTitle.setText(it.title)
            dialogBinding.etAmount.setText(it.amount.toString())
            dialogBinding.etCategory.setText(it.category, false)
            dialogBinding.etLocation.setText(it.location)
            expenseDate = it.date
        } ?: run {
            updateCategoryAdapter("EXPENSE")
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
                            0 -> startCamera()
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
                val type = dialogBinding.etType.text.toString()

                if (title.isNotEmpty() && amount != null && category.isNotEmpty()) {
                    if (existingExpense == null) {
                        val expense = Expense(
                            title = title,
                            amount = amount,
                            date = expenseDate,
                            category = category,
                            location = location,
                            type = type
                        )
                        expenseViewModel.insert(expense)
                    } else {
                        val updatedExpense = existingExpense.copy(
                            title = title,
                            amount = amount,
                            category = category,
                            date = expenseDate,
                            location = location,
                            type = type
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
