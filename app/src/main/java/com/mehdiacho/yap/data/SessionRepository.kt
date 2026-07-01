package com.mehdiacho.yap.data

import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    val allSessions: Flow<List<Session>> = sessionDao.getAllSessions()

    fun getSessionById(id: String): Flow<Session?> {
        return sessionDao.getSessionById(id)
    }

    suspend fun insertSession(session: Session) {
        sessionDao.insertSession(session)
    }

    suspend fun updateSession(session: Session) {
        sessionDao.updateSession(session)
    }

    suspend fun deleteSession(id: String) {
        sessionDao.deleteSession(id)
    }
}
