package com.poweruserhub.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.poweruserhub.app.service.LockDatabaseHelper
import com.poweruserhub.app.service.ShellService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockEnforcementWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dbHelper = LockDatabaseHelper(applicationContext)
        val shellService = ShellService(applicationContext)
        val activeLocks = dbHelper.getAllLocks().filter { it.isEnabled }
        
        if (activeLocks.isEmpty()) {
            return Result.success()
        }

        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm:ss a", Locale.getDefault())
        
        for (lock in activeLocks) {
            try {
                val currentValue = shellService.readSetting(lock.namespace, lock.key)
                val timestamp = sdf.format(Date())
                
                if (currentValue == lock.desiredValue) {
                    dbHelper.updateLockStatus(lock.key, "Verified", timestamp)
                } else {
                    // Diverged! Attempt restoration
                    val writeResult = shellService.writeSetting(lock.namespace, lock.key, lock.desiredValue)
                    val newValue = shellService.readSetting(lock.namespace, lock.key)
                    
                    if (newValue == lock.desiredValue) {
                        dbHelper.updateLockStatus(lock.key, "Restored", timestamp)
                    } else {
                        val status = if (writeResult.isSuccess) "Failed (OS restricted)" else "Failed (${writeResult.stderr.take(20)})"
                        dbHelper.updateLockStatus(lock.key, status, timestamp)
                    }
                }
            } catch (e: Exception) {
                val timestamp = sdf.format(Date())
                dbHelper.updateLockStatus(lock.key, "Error: ${e.message?.take(30)}", timestamp)
            }
        }

        return Result.success()
    }
}
