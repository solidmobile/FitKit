package com.example.fit_kit

import android.app.Activity
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataPoint
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.data.Session
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.request.SessionReadRequest
import com.google.android.gms.fitness.result.DataReadResponse
import com.google.android.gms.fitness.result.SessionReadResponse
import android.content.Intent
import android.content.Context
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.util.concurrent.TimeUnit


class FitKitPlugin(private var channel: MethodChannel? = null) : MethodCallHandler,FlutterPlugin,ActivityResultListener,ActivityAware {

    private lateinit var registrar : Registrar
    private lateinit var context: Context
    private var activity: Activity? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "fit_kit")
        channel?.setMethodCallHandler(this);
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        activity = null
    }

    companion object {
        private const val TAG = "FitKit"
        private const val GOOGLE_FIT_REQUEST_CODE = 1111

        @Suppress("unused")
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "fit_kit")
            val plugin = FitKitPlugin(channel)
            registrar.addActivityResultListener(plugin)
            channel.setMethodCallHandler(plugin)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.d("FLUTTER_HEALTH", requestCode.toString())
        Log.d("FLUTTER_HEALTH", resultCode.toString())
        if (requestCode == GOOGLE_FIT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d("FLUTTER_HEALTH", "Access Granted!")
                mResult?.success(true)
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.d("FLUTTER_HEALTH", "Access Denied!")
                mResult?.success(false)
            }
        }
        return false
    }

    private var mResult: Result? = null

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "hasPermissions" -> {
                    val request = PermissionsRequest.fromCall(call)
                    hasPermissions(request, result)
                }
                "requestPermissions" -> {
                    val request = PermissionsRequest.fromCall(call)
                    requestPermissions(request, result)
                }
                "revokePermissions" -> revokePermissions(result)
                "read" -> {
                    val request = ReadRequest.fromCall(call)
                    read(request, result)
                }
                else -> result.notImplemented()
            }
        } catch (e: Throwable) {
            result.error(TAG, e.message, null)
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        if (channel == null) {
            return
        }
        binding.addActivityResultListener(this)
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        if (channel == null) {
            return
        }
        activity = null
    }

    private fun hasPermissions(request: PermissionsRequest, result: Result) {
        if (activity == null) {
            result.success(false)
            return
        }

        val options = FitnessOptions.builder()
                .addDataTypes(request.types.map { it.dataType })
                .build()

        mResult = result

        val isGranted = hasOAuthPermission(options)
        mResult?.success(isGranted)
    }

    private fun requestPermissions(request: PermissionsRequest, result: Result) {
        if (activity == null) {
            result.success(false)
            return
        }

        val options = FitnessOptions.builder()
                .addDataTypes(request.types.map { it.dataType })
                .build()

        mResult = result
        requestOAuthPermissions(options, {
            //result.success(true)
            mResult?.success(true)
        }, {
            //result.success(false)
            mResult?.success(false)
        })
    }

    /**
     * let's wait for some answers
     * https://github.com/android/fit-samples/issues/28#issuecomment-557865949
     */
    private fun revokePermissions(result: Result) {
        val fitnessOptions = FitnessOptions.builder()
                .build()

        if (!hasOAuthPermission(fitnessOptions)) {
            result.success(null)
            return
        }

        Fitness.getConfigClient(activity!!.applicationContext, GoogleSignIn.getLastSignedInAccount(activity)!!)
                .disableFit()
                .continueWithTask {
                    val signInOptions = GoogleSignInOptions.Builder()
                            .addExtension(fitnessOptions)
                            .build()
                    GoogleSignIn.getClient(activity!!.applicationContext, signInOptions)
                            .revokeAccess()
                }
                .addOnSuccessListener { result.success(null) }
                .addOnFailureListener { e ->
                    if (!hasOAuthPermission(fitnessOptions)) {
                        result.success(null)
                    } else {
                        result.error(TAG, e.message, null)
                    }
                }
    }

    private fun read(request: ReadRequest<*>, result: Result) {
        val options = FitnessOptions.builder()
                .addDataType(request.type.dataType)
                .build()

        requestOAuthPermissions(options, {
            when (request) {
                is ReadRequest.Sample -> readSample(request, result)
                is ReadRequest.Activity -> readSession(request, result)
            }
        }, {
            result.error(TAG, "User denied permission access", null)
        })
    }

    private fun requestOAuthPermissions(fitnessOptions: FitnessOptions, onSuccess: () -> Unit, onError: () -> Unit) {

        val isGranted = hasOAuthPermission(fitnessOptions)

        /// Not granted? Ask for permission
        if (!isGranted && activity != null) {
            GoogleSignIn.requestPermissions(
                    activity!!,
                    GOOGLE_FIT_REQUEST_CODE,
                    GoogleSignIn.getLastSignedInAccount(activity),
                    fitnessOptions)
        }
        /// Permission already granted
        else {
            onSuccess()
        }
    }

    private fun hasOAuthPermission(fitnessOptions: FitnessOptions): Boolean {
        return GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(activity), fitnessOptions)
    }

    private fun readSample(request: ReadRequest<Type.Sample>, result: Result) {
        Log.d(TAG, "readSample: ${request.type}")
        Log.d(TAG, "INTERVAL: ${request.interval}");
        val readRequest = DataReadRequest.Builder()
                .read(request.type.dataType)
                .also { builder ->
                    when (request.limit != null) {
                        true -> builder.setLimit(request.limit)
                        else -> builder.bucketByTime(request.interval, TimeUnit.MINUTES)
                    }
                }
                .setTimeRange(request.dateFrom.time, request.dateTo.time, TimeUnit.MILLISECONDS)
                .enableServerQueries()
                .build()

        Fitness.getHistoryClient(activity!!.applicationContext, GoogleSignIn.getLastSignedInAccount(activity)!!)
                .readData(readRequest)
                .addOnSuccessListener { response -> onSuccess(response, result) }
                .addOnFailureListener { e -> result.error(TAG, e.message, null) }
                .addOnCanceledListener { result.error(TAG, "GoogleFit Cancelled", null) }
    }

    private fun onSuccess(response: DataReadResponse, result: Result) {
        (response.dataSets + response.buckets.flatMap { it.dataSets })
                .filterNot { it.isEmpty }
                .flatMap { it.dataPoints }
                .map(::dataPointToMap)
                .let(result::success)
    }

    @Suppress("IMPLICIT_CAST_TO_ANY")
    private fun dataPointToMap(dataPoint: DataPoint): Map<String, Any> {
        val field = dataPoint.dataType.fields.first()
        val source = dataPoint.originalDataSource.streamName

        return mapOf(
                "value" to dataPoint.getValue(field).let { value ->
                    when (value.format) {
                        Field.FORMAT_FLOAT -> value.asFloat()
                        Field.FORMAT_INT32 -> value.asInt()
                        else -> TODO("for future fields")
                    }
                },
                "date_from" to dataPoint.getStartTime(TimeUnit.MILLISECONDS),
                "date_to" to dataPoint.getEndTime(TimeUnit.MILLISECONDS),
                "source" to source,
                "user_entered" to (source == "user_input")
        )
    }

    private fun readSession(request: ReadRequest<Type.Activity>, result: Result) {
        Log.d(TAG, "readSession: ${request.type.activity}")

        val readRequest = SessionReadRequest.Builder()
                .read(request.type.dataType)
                .setTimeInterval(request.dateFrom.time, request.dateTo.time, TimeUnit.MILLISECONDS)
                .readSessionsFromAllApps()
                .enableServerQueries()
                .build()

        Fitness.getSessionsClient(activity!!.applicationContext, GoogleSignIn.getLastSignedInAccount(activity)!!)
                .readSession(readRequest)
                .addOnSuccessListener { response -> onSuccess(request, response, result) }
                .addOnFailureListener { e -> result.error(TAG, e.message, null) }
                .addOnCanceledListener { result.error(TAG, "GoogleFit Cancelled", null) }
    }

    private fun onSuccess(request: ReadRequest<Type.Activity>, response: SessionReadResponse, result: Result) {
        response.sessions.filter { request.type.activity == it.activity }
                .let { list ->
                    when (request.limit != null) {
                        true -> list.takeLast(request.limit)
                        else -> list
                    }
                }
                .map { session -> sessionToMap(session, response.getDataSet(session)) }
                .let(result::success)
    }

    private fun sessionToMap(session: Session, dataSets: List<DataSet>): Map<String, Any> {
        // from all data points find the top used streamName
        val source = dataSets.asSequence()
                .filterNot { it.isEmpty }
                .flatMap { it.dataPoints.asSequence() }
                .filterNot { it.originalDataSource.streamName.isNullOrEmpty() }
                .groupingBy { it.originalDataSource.streamName }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: session.name ?: ""

        return mapOf(
                "value" to session.getValue(),
                "date_from" to session.getStartTime(TimeUnit.MILLISECONDS),
                "date_to" to session.getEndTime(TimeUnit.MILLISECONDS),
                "source" to source,
                "user_entered" to (source == "user_input")
        )
    }
}
