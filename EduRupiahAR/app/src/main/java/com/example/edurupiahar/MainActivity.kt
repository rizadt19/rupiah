package com.example.edurupiahar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.edurupiahar.data.AppDatabase
import com.example.edurupiahar.data.CurrencyDao
import com.example.edurupiahar.data.CurrencyInfo
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
// import com.google.ar.core.HitResult // Not strictly needed if only tapping for fixed anchor
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Main activity for the EduRupiahAR application.
 * Handles ARCore session setup, camera permission, ML Kit image labeling,
 * Room database interaction, AR overlay display, and the quiz feature.
 */
class MainActivity : AppCompatActivity() {

    private var arFragment: ArFragment? = null
    private var arSession: Session? = null
    private var userRequestedInstall = true // Flag for ARCore installation request

    private lateinit var imageLabeler: ImageLabeler // For ML Kit image labeling
    private lateinit var currencyDao: CurrencyDao // DAO for accessing currency data

    private var frameCounter = 0 // Counter for processing frames at intervals
    private val frameProcessingInterval = 10 // Process every 10th frame for ML Kit

    private var currentInfoCardNode: AnchorNode? = null // Holds the currently displayed AR card
    private var currentCurrencyInfoForQuiz: CurrencyInfo? = null // Holds currency info for the active quiz question
    private lateinit var gestureDetector: GestureDetector // Handles tap gestures on the AR scene

    // UI Elements
    private lateinit var textViewMlOutput: TextView
    private lateinit var textViewUserGuidance: TextView
    private lateinit var buttonToggleQuiz: Button
    private lateinit var textViewQuizQuestion: TextView
    private lateinit var linearLayoutQuizOptions: LinearLayout
    private lateinit var buttonOption1: Button
    private lateinit var buttonOption2: Button
    private lateinit var buttonOption3: Button

    private var isQuizModeActive = false // Tracks if the quiz mode is currently active
    private var allCurrencyDenominations: List<Int> = emptyList() // Cached list of all denominations for quiz

    // --- New members for reactive display ---
    private val triggeringLabels = setOf("Paper", "Text", "Pattern", "Banknote", "Currency", "Money", "Rectangle") // Labels that trigger auto-display
    private var lastAutoCardDisplayTime: Long = 0 // Timestamp of the last auto-displayed card
    private val autoCardDisplayCooldown = 5000L // 5 seconds cooldown for auto-display
    // --- End of new members ---

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
        private const val TAG = "EduRupiahAR_MainActivity" // Logcat tag
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        textViewMlOutput = findViewById(R.id.textViewMlOutput)
        textViewUserGuidance = findViewById(R.id.textViewUserGuidance)
        buttonToggleQuiz = findViewById(R.id.buttonToggleQuiz)
        textViewQuizQuestion = findViewById(R.id.textViewQuizQuestion)
        linearLayoutQuizOptions = findViewById(R.id.linearLayoutQuizOptions)
        buttonOption1 = findViewById(R.id.buttonOption1)
        buttonOption2 = findViewById(R.id.buttonOption2)
        buttonOption3 = findViewById(R.id.buttonOption3)

        // Initialize Database and DAO
        val appDatabase = AppDatabase.getDatabase(this, lifecycleScope)
        currencyDao = appDatabase.currencyDao()

        // Fetch all denominations for quiz generation in the background
        lifecycleScope.launch(Dispatchers.IO) {
            allCurrencyDenominations = currencyDao.getAll().map { it.denominationValue }.distinct()
        }

        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment?
        if (arFragment == null) {
            Toast.makeText(this, "ArFragment is null. Check layout file.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        // Initialize ML Kit ImageLabeler
        imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

        // Check for camera permission and initialize AR session
        if (!checkCameraPermission()) requestCameraPermission() else initializeArSession()

        setupTapListener()
        setupQuizButtonListener()
        
        textViewUserGuidance.text = "Point your camera at a flat surface with a bill. The app will try to show info."
        textViewUserGuidance.visibility = View.VISIBLE
    }

    /**
     * Checks if the camera permission has been granted.
     * @return True if permission is granted, false otherwise.
     */
    private fun checkCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Requests camera permission from the user.
     */
    private fun requestCameraPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)

    /**
     * Initializes the ARCore session.
     * Checks for ARCore availability and requests installation if needed.
     * Configures the session for plane detection and light estimation.
     */
    @SuppressLint("MissingPermission") // Permission is checked by checkCameraPermission and onRequestPermissionsResult
    private fun initializeArSession() {
        if (arSession == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        arSession = Session(this)
                        val config = Config(arSession).apply {
                            focusMode = Config.FocusMode.AUTO
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL // Enable plane detection
                            lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY // Optional: for better rendering
                        }
                        arSession?.configure(config)
                        arFragment?.arSceneView?.setupSession(arSession)
                        Toast.makeText(this, "ARCore session initialized.", Toast.LENGTH_LONG).show()
                        // Set up ARCore frame update listener
                        arFragment?.arSceneView?.scene?.addOnUpdateListener { onFrameUpdate() }
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        // ARCore installation or update is needed.
                        userRequestedInstall = false // Prevent multiple install requests
                        Toast.makeText(this, "ARCore installation required. Please install ARCore and restart.", Toast.LENGTH_LONG).show()
                        // Optionally, guide user to Play Store or finish activity
                    }
                }
            } catch (e: Exception) { // Catches various ARCore exceptions
                Log.e(TAG, "Error initializing AR session", e)
                Toast.makeText(this, "Error initializing AR: ${e.message}", Toast.LENGTH_LONG).show()
                finish() // Cannot proceed without ARCore
            }
        }
    }

    /**
     * Sets up the tap listener for the AR scene to handle user interactions.
     */
    private fun setupTapListener() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                Log.d(TAG, "Screen tapped. Quiz mode: $isQuizModeActive")
                textViewUserGuidance.visibility = View.GONE // Hide guidance on any tap
                handleTap(e)
                return true
            }
        })
        // Attach the gesture detector to the AR scene view
        arFragment?.arSceneView?.scene?.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
    }

    /**
     * Sets up the listener for the quiz toggle button.
     * Manages UI changes when entering or exiting quiz mode.
     */
    private fun setupQuizButtonListener() {
        buttonToggleQuiz.setOnClickListener {
            isQuizModeActive = !isQuizModeActive
            if (isQuizModeActive) {
                buttonToggleQuiz.text = "End Quiz"
                textViewQuizQuestion.text = "Tap a currency on a surface to start quiz!" // Initial quiz prompt
                textViewQuizQuestion.visibility = View.VISIBLE
                linearLayoutQuizOptions.visibility = View.GONE // Options hidden until a question is ready
                textViewMlOutput.visibility = View.GONE // Hide ML output during quiz
                textViewUserGuidance.visibility = View.GONE // Hide general guidance
                removeArInfoCard() // Clear any existing info card
            } else {
                buttonToggleQuiz.text = "Start Quiz"
                textViewQuizQuestion.visibility = View.GONE
                linearLayoutQuizOptions.visibility = View.GONE
                textViewMlOutput.visibility = View.VISIBLE // Show ML output again
                // textViewUserGuidance.visibility = View.VISIBLE; // Or keep it hidden
                removeArInfoCard()
            }
        }

        // Listener for quiz option buttons
        val optionClickListener = View.OnClickListener { view ->
            val selectedAnswer = (view as Button).text.toString()
            val correctAnswer = currentCurrencyInfoForQuiz?.denominationValue.toString()
            if (selectedAnswer == correctAnswer) {
                Toast.makeText(this, "Correct!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Incorrect. The answer was Rp $correctAnswer", Toast.LENGTH_LONG).show()
            }
            // Reset for next question
            linearLayoutQuizOptions.visibility = View.GONE
            textViewQuizQuestion.text = "Tap another currency for the next question!"
            removeArInfoCard() // User needs to tap a new currency/surface for a new quiz item
        }
        buttonOption1.setOnClickListener(optionClickListener)
        buttonOption2.setOnClickListener(optionClickListener)
        buttonOption3.setOnClickListener(optionClickListener)
    }

    /**
     * Handles a tap event on the AR scene.
     * Performs a hit test to find a plane and creates an anchor.
     * Then calls [displayCurrencyCard] to show information or start a quiz.
     * @param motionEvent The motion event from the tap.
     */
    private fun handleTap(motionEvent: MotionEvent) {
        val frame = arFragment?.arSceneView?.arFrame ?: return
        if (frame.camera.trackingState == TrackingState.TRACKING) {
            // Perform hit test against detected planes.
            val hitResult = frame.hitTest(motionEvent).firstOrNull {
                val trackable = it.trackable
                (trackable is Plane && trackable.isPoseInPolygon(it.hitPose) &&
                        (Plane.Type.HORIZONTAL_UPWARD_FACING.isAssignableFrom(trackable.type) || // Prefer horizontal
                         Plane.Type.VERTICAL.isAssignableFrom(trackable.type))) // Allow vertical planes too
            }

            val anchor = hitResult?.createAnchor() // Create anchor if a plane is hit

            // Determine which currency to display based on quiz mode or default.
            // In a real app, this label would come from a currency detection model.
            val labelToDisplay = if (isQuizModeActive) {
                // For quiz, pick a random currency for the user to identify.
                allCurrencyDenominations.randomOrNull()?.let { "IDR$it" } ?: "IDR2000" // Fallback if list is empty
            } else {
                 "IDR100000" // Default for manual tap when not in quiz mode
            }
            displayCurrencyCard(labelToDisplay, anchor) // Anchor can be null if no plane was hit
        }
    }
    
    /**
     * Displays the AR information card for a given currency key at a specified anchor.
     * If the anchor is null, it attempts to find a suitable plane or places the card in front of the camera.
     * Fetches currency details from the database.
     * @param currencyKey The identifier for the currency (e.g., "IDR2000").
     * @param anchor The ARCore Anchor to attach the card to. Can be null.
     */
    private fun displayCurrencyCard(currencyKey: String, anchor: Anchor?) {
        Log.d(TAG, "Attempting to display card for: $currencyKey. Anchor is ${if (anchor == null) "null" else "not null"}")
        textViewUserGuidance.visibility = View.GONE // Hide guidance when a card is about to be shown

        var finalAnchor = anchor
        // If no anchor is provided (e.g., from ML Kit auto-trigger), try to find one.
        if (finalAnchor == null) {
            val frame = arFragment?.arSceneView?.arFrame
            if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                 // Attempt to hit-test at the center of the screen.
                val width = arFragment?.arSceneView?.width ?: 0
                val height = arFragment?.arSceneView?.height ?: 0
                val centerHits = frame.hitTest(width / 2f, height / 2f)
                val planeHit = centerHits.firstOrNull {
                    val trackable = it.trackable
                    (trackable is Plane && trackable.isPoseInPolygon(it.hitPose) &&
                            (Plane.Type.HORIZONTAL_UPWARD_FACING.isAssignableFrom(trackable.type) ||
                             Plane.Type.VERTICAL.isAssignableFrom(trackable.type)))
                }
                if (planeHit != null) {
                    finalAnchor = planeHit.createAnchor()
                    Log.d(TAG, "Created anchor from screen center hit test for reactive display.")
                } else {
                    // Fallback: Place in front of the camera if screen center hit test fails.
                    val cameraPose = frame.camera.pose
                    val translation = floatArrayOf(0f, -0.1f, -0.4f) // 0.4m in front, 0.1m down
                    finalAnchor = arSession?.createAnchor(cameraPose.compose(com.google.ar.core.Pose(translation, floatArrayOf(0f,0f,0f,1f))).extractTranslation())
                    Log.d(TAG, "Created anchor at fixed distance as fallback for reactive display.")
                }
            }
        }

        if (finalAnchor == null) {
            Log.e(TAG, "Could not create or find a suitable anchor for the card.")
            Toast.makeText(this, "Could not place info card. Try pointing at a flat surface.", Toast.LENGTH_LONG).show()
            return
        }

        // Fetch currency information and then create the AR card.
        lifecycleScope.launch {
            val currencyInfo = fetchCurrencyInfo(currencyKey)
            currentCurrencyInfoForQuiz = currencyInfo // Store for potential quiz use

            if (currencyInfo != null) {
                Log.d(TAG, "Currency info found: ${currencyInfo.denominationValue}")
                withContext(Dispatchers.Main) { // Switch to Main thread for UI/Sceneform updates
                    createArInfoCardNode(currencyInfo, finalAnchor) // finalAnchor is now guaranteed non-null or returned
                    if (isQuizModeActive) {
                        setupQuizForCurrency(currencyInfo)
                    }
                }
            } else {
                Log.e(TAG, "Currency info not found for key: $currencyKey")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Info not found for $currencyKey", Toast.LENGTH_LONG).show()
                    if (isQuizModeActive) { // Update quiz UI if info not found
                        textViewQuizQuestion.text = "Info not found for $currencyKey. Tap another."
                        linearLayoutQuizOptions.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Sets up the quiz UI for the given [CurrencyInfo].
     * Displays the question and populates answer options.
     * @param currencyInfo The [CurrencyInfo] object for the current quiz item.
     */
    private fun setupQuizForCurrency(currencyInfo: CurrencyInfo) {
        textViewQuizQuestion.text = "What is the nominal value of this currency?"
        textViewQuizQuestion.visibility = View.VISIBLE

        val correctAnswer = currencyInfo.denominationValue.toString()
        val incorrectOptions = mutableListOf<String>()
        val otherDenominations = allCurrencyDenominations.filter { it != currencyInfo.denominationValue }

        // Generate two distinct incorrect options.
        incorrectOptions.addAll(otherDenominations.shuffled().take(2).map { it.toString() })
        // Ensure we have 2 distinct incorrect options, even if few denominations exist
        if (incorrectOptions.size < 2) {
            var fallbackOption1 = currencyInfo.denominationValue + 1000
            if (fallbackOption1 == currencyInfo.denominationValue) fallbackOption1 += 1000 // Avoid same as correct
            incorrectOptions.add(fallbackOption1.toString())
        }
         if (incorrectOptions.size < 2) {
             var fallbackOption2 = currencyInfo.denominationValue - 1000
             if (fallbackOption2 <=0) fallbackOption2 = currencyInfo.denominationValue + 2000
             if (fallbackOption2 == currencyInfo.denominationValue || incorrectOptions.contains(fallbackOption2.toString())) {
                 fallbackOption2 = currencyInfo.denominationValue + 3000 // Try another variation
             }
             incorrectOptions.add(fallbackOption2.toString())
        }
        // Final distinct check and trim if more than 2 somehow
        val finalIncorrectOptions = incorrectOptions.distinct().filter { it != correctAnswer }.take(2)
        
        val options = (finalIncorrectOptions + correctAnswer).shuffled()

        buttonOption1.text = options.getOrElse(0) { "Opt A" } // Fallback text
        buttonOption2.text = options.getOrElse(1) { "Opt B" }
        buttonOption3.text = options.getOrElse(2) { "Opt C" }

        linearLayoutQuizOptions.visibility = View.VISIBLE
    }

    /**
     * Fetches [CurrencyInfo] from the database based on the detected label.
     * This is a suspend function and should be called from a coroutine.
     * @param currencyKey The label associated with the currency (e.g., "IDR2000").
     * @return The [CurrencyInfo] object if found, otherwise null.
     */
    private suspend fun fetchCurrencyInfo(currencyKey: String): CurrencyInfo? = withContext(Dispatchers.IO) { currencyDao.findByDetectedLabel(currencyKey) }
    
    /**
     * Removes the currently displayed AR information card from the scene.
     */
    private fun removeArInfoCard() {
        currentInfoCardNode?.anchor?.detach() // Detach anchor from the world
        currentInfoCardNode?.let { arFragment?.arSceneView?.scene?.removeChild(it) } // Remove node from scene
        currentInfoCardNode?.isEnabled = false // Disable the node
        currentInfoCardNode = null
        currentCurrencyInfoForQuiz = null // Clear quiz context
        Log.d(TAG, "AR info card removed.")
    }

    /**
     * Creates and displays an AR information card (ViewRenderable) for the given [CurrencyInfo]
     * at the specified [Anchor]. The card is made to face the camera.
     * @param currencyInfo The data to display on the card.
     * @param anchor The ARCore Anchor where the card will be placed.
     */
    private fun createArInfoCardNode(currencyInfo: CurrencyInfo, anchor: Anchor) {
        removeArInfoCard() // Ensure only one card is displayed at a time

        ViewRenderable.builder().setView(this, R.layout.ar_info_card).build()
            .thenAccept { viewRenderable ->
                viewRenderable.isShadowCaster = false; viewRenderable.isShadowReceiver = false
                // Populate the card's views
                viewRenderable.view.apply {
                    findViewById<TextView>(R.id.textViewNominal).text = "Nominal: Rp ${currencyInfo.denominationValue}"
                    findViewById<TextView>(R.id.textViewYear).text = "Year: ${currencyInfo.yearOfIssue}"
                    findViewById<TextView>(R.id.textViewSecurityFeatures).text = currencyInfo.securityFeatures
                    findViewById<TextView>(R.id.textViewDescription).text = currencyInfo.description
                }

                val anchorNode = AnchorNode(anchor).apply {
                    // Make the card face the camera
                    val scene = arFragment!!.arSceneView.scene
                    worldRotation = Quaternion.lookRotation(Vector3.subtract(scene.camera.worldPosition, worldPosition), Vector3.up())
                    // Slight upward offset for better visibility
                    localPosition = Vector3(0f, 0.05f, 0f)
                }
                
                // Create a transformable node to allow user interaction (moving, scaling)
                TransformableNode(arFragment!!.transformationSystem).apply {
                    renderable = viewRenderable
                    setParent(anchorNode)
                }
                
                arFragment?.arSceneView?.scene?.addChild(anchorNode)
                currentInfoCardNode = anchorNode // Store reference to the current card
                // transformableNode.select() // Don't auto-select if quiz might appear, let user tap if they want to interact
                Log.d(TAG, "New info card created for ${currencyInfo.detectedLabel}")
            }
            .exceptionally { throwable -> Log.e(TAG, "Error building ViewRenderable for AR card", throwable); null }
    }

    /**
     * Called on each AR frame update. Handles ML Kit image processing if conditions are met.
     * (Not in quiz mode, cooldown passed, no card currently shown).
     */
    private fun onFrameUpdate() {
        val frame = arFragment?.arSceneView?.arFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return // Only process if ARCore is tracking

        frameCounter++
        if (frameCounter < frameProcessingInterval) return // Process frames at defined interval
        frameCounter = 0

        // Conditions for automatic ML Kit processing and card display:
        // 1. Not in quiz mode.
        // 2. Cooldown period since last auto-display has passed.
        if (!isQuizModeActive && (System.currentTimeMillis() - lastAutoCardDisplayTime > autoCardDisplayCooldown)) {
            try {
                val cameraImage: Image = frame.acquireCameraImage() // Acquire image for processing
                val bitmap = cameraImageToBitmap(cameraImage)
                val rotationDegrees = getRotationDegrees(cameraImage, frame)
                val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
                cameraImage.close() // IMPORTANT: Close the image quickly

                imageLabeler.process(inputImage)
                    .addOnSuccessListener { labels ->
                        displayMlKitLabels(labels) // Always display general ML labels if not in quiz mode

                        // Check if any detected label is a "triggering" label with high confidence
                        val foundTriggerLabel = labels.any { label ->
                            triggeringLabels.contains(label.text) && label.confidence > 0.7f
                        }
                        if (foundTriggerLabel && currentInfoCardNode == null) { // Only trigger if no card is currently shown
                            Log.d(TAG, "Triggering label found by ML Kit. Attempting to display IDR2000 card automatically.")
                            displayCurrencyCard("IDR2000", null) // Pass null anchor; function will try to find one
                            lastAutoCardDisplayTime = System.currentTimeMillis() // Reset cooldown timer
                            textViewUserGuidance.visibility = View.GONE // Hide guidance as app is now reacting
                        }
                    }
                    .addOnFailureListener { e -> Log.e(TAG, "Image labeling failed", e); textViewMlOutput.text = "ML: Error" }
            } catch (e: Exception) { // Catch potential errors during image acquisition/processing
                Log.e(TAG, "Error during AR frame processing for ML Kit: ${e.message}", e)
            }
        } else if (isQuizModeActive) {
             textViewMlOutput.text = "ML Output: Quiz Active" // Indicate quiz mode in ML output area
        } else {
            // Cooldown active or card already shown.
            // Optionally, could still update ML labels if no card is shown, but currently this path does nothing.
        }
    }

    /**
     * Displays the top ML Kit detected image labels in the UI.
     * @param labels A list of [ImageLabel] objects from ML Kit.
     */
    private fun displayMlKitLabels(labels: List<ImageLabel>) {
        if (isQuizModeActive) return // Don't show ML labels during quiz

        if (labels.isEmpty()) {
            textViewMlOutput.text = "ML Output: Nothing detected"
            return
        }
        val topLabels = labels.take(3) // Show top 3 labels
            .joinToString(separator = "\n") { "${it.text} (${String.format("%.0f%%", it.confidence * 100)})" }
        textViewMlOutput.text = "ML Output:\n$topLabels"
    }

    /**
     * Converts an ARCore [Image] (YUV_420_888 format) to a [Bitmap].
     * @param image The ARCore [Image] to convert.
     * @return The converted [Bitmap].
     * @throws IllegalArgumentException if the image format is not YUV_420_888.
     */
    private fun cameraImageToBitmap(image: Image): Bitmap {
        if (image.format != ImageFormat.YUV_420_888) throw IllegalArgumentException("Unsupported image format: ${image.format}")
        
        val yBuffer = image.planes[0].buffer; val uBuffer = image.planes[1].buffer; val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize) // NV21 format has V plane before U plane after Y
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out) // Compress to JPEG
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) // Decode JPEG to Bitmap
    }

    /**
     * Gets the rotation degrees needed for ML Kit, based on ARCore frame's camera sensor rotation.
     * @param image The ARCore [Image]. (Currently unused but kept for signature consistency if needed later)
     * @param frame The current ARCore [Frame].
     * @return The rotation in degrees.
     */
    private fun getRotationDegrees(image: Image, frame: Frame): Int = frame.camera.imageSensorRotation

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) initializeArSession()
            else { Toast.makeText(this, "Camera permission is required to use this app.", Toast.LENGTH_LONG).show(); finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkCameraPermission() && arSession == null) { userRequestedInstall = true; initializeArSession() }
        try { arSession?.resume(); arFragment?.arSceneView?.resume(); Log.d(TAG, "AR Session resumed.") }
        catch (e: Exception) { Log.e(TAG, "Error resuming AR session", e) }
    }

    override fun onPause() {
        super.onPause()
        try { arSession?.pause(); arFragment?.arSceneView?.pause(); Log.d(TAG, "AR Session paused.") }
        catch (e: Exception) { Log.e(TAG, "Error pausing AR session", e) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { arSession?.close(); arSession = null; Log.d(TAG, "AR Session closed.") }
        catch (e: Exception) { Log.e(TAG, "Error closing AR session", e) }
    }
}
