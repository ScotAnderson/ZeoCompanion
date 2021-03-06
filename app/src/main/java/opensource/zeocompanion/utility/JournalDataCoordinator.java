package opensource.zeocompanion.utility;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.myzeo.android.api.data.ZeoDataContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import opensource.zeocompanion.ZeoCompanionApplication;
import opensource.zeocompanion.database.CompanionAttributesRec;
import opensource.zeocompanion.database.CompanionDatabaseContract;
import opensource.zeocompanion.database.CompanionSleepEpisodeEventsParsedRec;
import opensource.zeocompanion.database.CompanionSleepEpisodeInfoParsedRec;
import opensource.zeocompanion.database.CompanionSleepEpisodesRec;
import opensource.zeocompanion.zeo.ZAH_SleepRecord;
import opensource.zeocompanion.zeo.ZeoAppHandler;

// this class is responible for creating and maintaining the coordination between the ZeoApp's database
// and the ZeoCompanion App's database
public class JournalDataCoordinator implements ZeoAppHandler.ZAH_Listener {
    // member variables
    private Context mContext = null;
    private boolean alreadyRegisteredZAH = false;
    private int mDaypoint = 0;
    private Handler mMainActivityHandler = null;
    private CompanionSleepEpisodesRec mDaypoint_CSEs[] = { null, null, null};

    // member constants and other static content
    private static final String _CTAG = "JDU";
    SimpleDateFormat mJSB_sdf1 = new SimpleDateFormat("EEE HH:mm");
    SimpleDateFormat mJSB_sdf2 = new SimpleDateFormat("HH:mm");

    // constructor; the Activity or App context is needed for some methods
    public JournalDataCoordinator(Context context) {
        mContext = context;
    }

    // since the App remains active yet idle, the MainActivity will "wakeup" the Journal Data Coordinator,
    // allowing it to re-sync with in-process data and headband recording that got interrupted;
    // remember the interval of time can range from just 1 second or less to days;
    // the ZeoAppHandler will have been resynchronized before this handler
    public void resynchronize() {
        // first toss out anything that is now too old
        Log.d(_CTAG+".resynchronize","STARTING");
        long timeNow = System.currentTimeMillis();
        long time24HoursAgo = timeNow - 86400000L;
        if (mDaypoint_CSEs[0] != null) {
            if (mDaypoint_CSEs[0].rStartOfRecord_Timestamp <= time24HoursAgo) {
                mDaypoint_CSEs[0].destroy(); mDaypoint_CSEs[0] = null;
                //Log.d(_CTAG+".resynchronize","Yesterday CSE too old; removing from daypoint");
            }
        }
        if (mDaypoint_CSEs[1] != null) {
            if (mDaypoint_CSEs[1].rStartOfRecord_Timestamp <= time24HoursAgo) {
                mDaypoint_CSEs[1].destroy(); mDaypoint_CSEs[1] = null;
                //Log.d(_CTAG+".resynchronize","Today CSE too old; removing from daypoint");
            }
        }
        if (mDaypoint_CSEs[2] != null) {
            if (mDaypoint_CSEs[2].rStartOfRecord_Timestamp <= time24HoursAgo) {
                mDaypoint_CSEs[2].destroy(); mDaypoint_CSEs[2] = null;
                //Log.d(_CTAG+".resynchronize","Tomorrow CSE too old; removing from daypoint");
            }
        }

        // now if we've got nothing at all in the Daypoints, attempt a recovery from the database;
        // this likely will only be needed upon complete App restart or if the App has been not used for days
        ArrayList<CompanionSleepEpisodesRec> priorDbRecs = new ArrayList<CompanionSleepEpisodesRec>();
        if (mDaypoint_CSEs[0] == null && mDaypoint_CSEs[1] == null && mDaypoint_CSEs[2] == null) {
            // load up any CSE records that occurred within the last 36 hours, sorted in descending timestamp
            Log.d(_CTAG+".resynchronize","All daypoints are empty");
            long time36HoursAgo = timeNow - 129600000L;
            Cursor cursor = ZeoCompanionApplication.mDatabaseHandler.getAllCompanionSleepEpisodesRecsAfterDate(time36HoursAgo); // sorted in descending timestamp order
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    do {
                        CompanionSleepEpisodesRec sRec = new CompanionSleepEpisodesRec(cursor);
                        if (!sRec.isUnlinkedAndDead()) { priorDbRecs.add(sRec); }
                    } while (cursor.moveToNext());
                }
                cursor.close();
            }
            Log.d(_CTAG+".resynchronize","Located "+priorDbRecs.size()+" potential CSEs");

            // process the results of the recent records query
            if (priorDbRecs.size() > 0) {
                // there are some possibly relevant records found; remember they are in descending date order
                long time18oursAgo = timeNow - 64800000L;
                int endingStatesFlags = (CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_JOURNAL_EXPLICITEND |
                                        CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITEND);
                int statesFlagsToCheck = (CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITRECORD |
                                        CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_JOURNAL_EXPLICITSTART |
                                        CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITEND);
                int pos = 0;
                do {
                    CompanionSleepEpisodesRec sRec = priorDbRecs.get(pos);
                    if (sRec.rStartOfRecord_Timestamp > timeNow) {
                        // this record started in-the-future
                        if (sRec.rZeoSleepEpisode_ID != 0) {
                            mDaypoint_CSEs[1] = sRec;   // it is ZeoApp active, so it has to become the current Daypoint
                            Log.d(_CTAG+".resynchronize","Slotted future linked CSE ID "+sRec.rID+" into Today daypoint");
                        } else if (sRec.doEventsExist()) {
                            mDaypoint_CSEs[1] = sRec;   // Journal events are present, so it has to become the current Daypoint
                            Log.d(_CTAG+".resynchronize","Slotted future evented CSE ID "+sRec.rID+" into Today daypoint");
                        } else {
                            mDaypoint_CSEs[2] = sRec;   // it may have some pre-content, but its not yet active, so put this in the Tomorrow Daypoint
                            Log.d(_CTAG+".resynchronize","Slotted future CSE ID "+sRec.rID+" into Tomorrow daypoint");
                        }
                    } else if (sRec.rStartOfRecord_Timestamp >= time18oursAgo) {
                        // this record started in-the-past but only within the last 18 hours
                        if (sRec.rEndOfRecord_Timestamp != 0 || (sRec.rStatesFlag & endingStatesFlags) != 0) {
                            // this record is ended, so its for the Yesterday Daypoint and we are done looking at DB records
                            if ((sRec.rStatesFlag & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC) == 0 || !sRec.isZeoOnly()) {
                                // the CSE and/or the ZSE are not dead
                                if (mDaypoint_CSEs[0] == null)  {
                                    mDaypoint_CSEs[0] = sRec;
                                    Log.d(_CTAG+".resynchronize","Slotted ended CSE ID "+sRec.rID+" into Yesterday daypoint; stop processing CSEs");
                                }
                                break;
                            }
                        } else if ((sRec.rStatesFlag & statesFlagsToCheck) != 0 || sRec.rZeoSleepEpisode_ID != 0) {
                            // the record is active and not apparently ended
                            if (mDaypoint_CSEs[1] == null) {
                                mDaypoint_CSEs[1] = sRec;   // and the Today Daypoint is empty
                                Log.d(_CTAG+".resynchronize","Slotted active CSE ID "+sRec.rID+" into Today daypoint");
                            } else {
                                // however a more recent record already is in-place, so it cannot be made current and we are done searching
                                if (mDaypoint_CSEs[0] == null)  {
                                    mDaypoint_CSEs[0] = sRec;
                                    Log.d(_CTAG+".resynchronize","Slotted active CSE ID "+sRec.rID+" into Yesterday daypoint; stop processing CSEs");
                                }
                                break;
                            }
                        } else {
                            // it has not been recorded into and is just waiting
                            if (mDaypoint_CSEs[1] == null) {
                                mDaypoint_CSEs[1] = sRec;   // put it into the Today Daypoint
                                Log.d(_CTAG+".resynchronize","Slotted pending CSE ID "+sRec.rID+" into Today daypoint");
                            }
                        }
                    } else {
                        // this record started in-the-past more than 18 hours ago but less than 36 hours ago
                        if ((sRec.rStatesFlag & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC ) == 0 || !sRec.isZeoOnly()) {
                            // the CSE and/or the ZSE are not dead
                            if (mDaypoint_CSEs[0] == null) {
                                mDaypoint_CSEs[0] = sRec;   // put it into the Yesterday Daypoint and we are done
                                Log.d(_CTAG+".resynchronize","Slotted older CSE ID "+sRec.rID+" into Yesterday daypoint; stop processing CSEs");
                            }
                            break;
                        }
                    }
                    pos++;
                } while (pos < priorDbRecs.size());
            }
        }

        // then check whether a sleep session is in-progress from the ZeoApp's point-of-view
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State <= ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE) { return; }    // idle
        Log.d(_CTAG+".resynchronize","Zeo App is not Idle");

        // A sleep session is starting or is in-progress
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID != 0) {
            Log.d(_CTAG+".resynchronize","Zeo App has allocated a ZSE record and is therefore Recording or Ending");
            // the ZeoApp is not idle and has a record started; where is our matching record?
            for (int dp = -1; dp <= 1; dp++) {
                if (mDaypoint_CSEs[dp + 1] != null) {
                    if (mDaypoint_CSEs[dp + 1].rZeoSleepEpisode_ID == ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID) {
                        // found it
                        switch (dp) {
                            case -1:
                                // our prior CSE is still active; this can be a normal state if user has pressed DONE SLEEPING yet still has the headband on;
                                // or if Zeo App is at ENDING
                                // TODO should address situation when terminated ZSE gets re-activated when headband out-of-range then in-range while recording
                                if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State == ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING ||
                                        (mDaypoint_CSEs[0].rStatesFlag & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_JOURNAL_EXPLICITEND) != 0) {
                                    // this is the correct state; do nothing
                                    Log.d(_CTAG+".resynchronize","Active ZSE record has been found linked in Yesterday Ending CSE; no changes");
                                } else if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State < ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING) {
                                    // the ZeoApp is starting or recording and no endings have been indicated; so this state is wrong
                                    Log.d(_CTAG+".resynchronize","Active ZSE record has been found linked in Yesterday nonEnding CSE; move everything up");
                                    if (mDaypoint_CSEs[1] == null) {
                                        // Today slot is empty, so move into it
                                        mDaypoint_CSEs[1] = mDaypoint_CSEs[0];  // Today slot is empty, so just move back
                                        mDaypoint_CSEs[0] = null;
                                        dp = 0;
                                    } else {
                                        // Today slot has something in it, but so jam Today into Tomorrow (if anything is in Tomorrow its bitbucketed)
                                        if (mDaypoint_CSEs[2] != null) { mDaypoint_CSEs[2].destroy(); }
                                        mDaypoint_CSEs[2] = mDaypoint_CSEs[1];
                                        mDaypoint_CSEs[1] = mDaypoint_CSEs[0];
                                        mDaypoint_CSEs[0] = null;
                                        dp = 0;
                                    }
                                }
                                break;
                            case 0:
                                // this is exactly where it should be; do nothing
                                Log.d(_CTAG+".resynchronize","Active ZSE record has been found linked in Today CSE; no changes");
                                break;
                            case 1:
                                // somehow our future CSE is now active; move everything down
                                Log.d(_CTAG+".resynchronize","Active ZSE record has been found linked in Tomorrow CSE; move everything down");
                                if (mDaypoint_CSEs[0] != null) { mDaypoint_CSEs[0].destroy(); }
                                mDaypoint_CSEs[0] = mDaypoint_CSEs[1];
                                mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                                mDaypoint_CSEs[2] = null;
                                dp = 0;
                                break;
                        }
                        syncCSEtoZeoRecord(mDaypoint_CSEs[dp + 1], false, false);
                        return;
                    }
                }
            }
            // did not find the ZeoApp sleep episode ID in any Daypoint CSE, so force resync to the Today Daypoint;
            // the sync process will check the ZeoCompanion database for a match if possible
            Log.d(_CTAG+".resynchronize","ZSE record has no matching linked CSE in the daypoints");
            syncTodayDaypointToZeoRecord(false, false);
            return;
        }

        // Zeo App is not idle and has no Zeo Sleep Record yet (this should only occur in STARTING mode);
        // a STARTING-only CSE is not saved in the database but should be present in the daypoint CSEs unless the App was restarted
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State == ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING) {
            Log.d(_CTAG+".resynchronize","Zeo App is only at Starting");
            for (int dp = -1; dp <= 1; dp++) {
                if (mDaypoint_CSEs[dp + 1] != null) {
                    long contents = mDaypoint_CSEs[dp + 1].getContentsBitmap();
                    if ((contents & 0x0000F100) == 0x00001100) {
                        // found it
                        switch (dp) {
                            case -1:
                                // our prior CSE is still active as a STARTING record
                                Log.d(_CTAG+".resynchronize","CSE record in STARTING state has been found in Yesterday CSE; move everything up");
                                if (mDaypoint_CSEs[1] == null) {
                                    // Today slot is empty, so move into it
                                    mDaypoint_CSEs[1] = mDaypoint_CSEs[0];  // Today slot is empty, so just move back
                                    mDaypoint_CSEs[0] = null;
                                    dp = 0;
                                } else {
                                    // Today slot has something in it, but so jam Today into Tomorrow (if anything is in Tomorrow its bitbucketed)
                                    if (mDaypoint_CSEs[2] != null) { mDaypoint_CSEs[2].destroy(); }
                                    mDaypoint_CSEs[2] = mDaypoint_CSEs[1];
                                    mDaypoint_CSEs[1] = mDaypoint_CSEs[0];
                                    mDaypoint_CSEs[0] = null;
                                    dp = 0;
                                }
                                break;
                            case 0:
                                // this is exactly where it should be; do nothing
                                Log.d(_CTAG+".resynchronize","CSE record in STARTING state has been found in Today CSE; no changes");
                                break;
                            case 1:
                                // somehow our future CSE is now active; move everything down
                                Log.d(_CTAG+".resynchronize","CSE record in STARTING state has been found in Tomorrow CSE; move everything down");
                                if (mDaypoint_CSEs[0] != null) { mDaypoint_CSEs[0].destroy(); }
                                mDaypoint_CSEs[0] = mDaypoint_CSEs[1];
                                mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                                mDaypoint_CSEs[2] = null;
                                dp = 0;
                                break;
                        }
                        syncCSEtoZeoRecord(mDaypoint_CSEs[dp + 1], false, false);
                    }
                }
            }
        }
    }

    // called by MainActivity only if the end-user has chosen to utilize the sleep journal
    public void registerWithZeoAppHandler() {
        if (ZeoCompanionApplication.mZeoAppHandler != null && !alreadyRegisteredZAH) {
            ZeoCompanionApplication.mZeoAppHandler.setZAH_Listener(this);
            alreadyRegisteredZAH = true;
        }
    }

    // provides the JDC with access to the Journal Status Bar UI element; this can be NULL to remove access
    public void setMsgHandler(Handler handler) {
        mMainActivityHandler = handler;
    }

    // send a message to the UI to have the JSB update its status
    private void sendDoJSBupdateMsgToUI() {
        if (mMainActivityHandler != null) {
            Message msg = new Message();
            msg.what = ZeoCompanionApplication.MESSAGE_MAIN_UPDATE_JSB;
            mMainActivityHandler.sendMessage(msg);
        }
    }

    // send a message to the UI to have the JSB update its status
    private void sendDoAllUpdateMsgToUI() {
        if (mMainActivityHandler != null) {
            Message msg = new Message();
            msg.what = ZeoCompanionApplication.MESSAGE_MAIN_UPDATE_ALL;
            mMainActivityHandler.sendMessage(msg);
        }
    }

    /////////////////////////////////////////////////////////////////////////
    // Methods related to synchronization with the Zeo App
    /////////////////////////////////////////////////////////////////////////

    // Thread context: Main Thread
    // callback from the ZeoApp Handler; state change has occurred
    public void onZeoAppStateChange() {
        Log.d(_CTAG + ".zeoAppChg", "=====>INVOKED State=" + ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State);
        switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                // Zeo App changed to the STARTING or RECORDING state; sync if possible, else record an event for only STARTING (since it usually does not have a SleepEpisode_ID assigned yet)
                if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID != 0) {
                    Log.d(_CTAG + ".zeoAppChg", "Change to Starting or Recording: Zeo App has Sleep Record; sync to today daypoint");
                    syncTodayDaypointToZeoRecord(true, true);
                } else if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State == ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING)  {
                    Log.d(_CTAG + ".zeoAppChg", "Change to Starting: Zeo App not allocated sleep record; get/make today CSE and prep it");
                    CompanionSleepEpisodesRec sRec = getTodayDaypointCSECreateIfShould();
                    if (sRec != null) { syncCSEtoZeoRecord(sRec, true, true); }
                }
                break;

            case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                // Zeo App changed to ENDING state, record this in the proper Daypoint CSE (it could be Today or Yesterday); include preliminary end-of-sleep totals
                int dp = getDaypointWithZeoID(ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID);
                if (dp >= -1) {
                    Log.d(_CTAG + ".zeoAppChg", "Change to Ending: sync to daypoint "+dp);
                    syncCSEtoZeoRecord(mDaypoint_CSEs[dp+1], true, true);
                    if (dp == 0) {
                        Log.d(_CTAG + ".zeoAppChg", "Change to Ending: move today to yesterday");
                        resetDaypointForNewSleepSession();
                    }
                }
                break;

            case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_UNKNOWN:
            default:
                // Zeo App changed to Idle state
                if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_Prior_State >= ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING) {
                    // were we in recording or ending just before this
                    Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: prior was Recording or Ending");
                    CompanionSleepEpisodesRec sRec1 = getYesterdayDaypointCSE();
                    if (sRec1 != null) {
                        // do one last final sync to finalize whether the Zeo Sleep Record is dead or valid and has finalized totals from the headband
                        Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: has yesterday daypoint CSE; sync to yesterday daypoint");
                        syncCSEtoZeoRecord(sRec1, true, false);
                        if ((sRec1.rStatesFlag & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC) != 0 && sRec1.isZeoOnly()) {
                            // Zeo rec is dead and the CSE has no info other than Zeo info; remove it from the yesterday daypoint
                            Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: yesterday daypoint to be removed: CSE.deadrec="+(sRec1.rStatesFlag & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC)+" and CSE.isZeoOnly="+sRec1.isZeoOnly());
                            sRec1.destroy();
                            mDaypoint_CSEs[0] = null;
                            if (mDaypoint == -1) { sendDoAllUpdateMsgToUI(); }  // if yesterday daypoint is showing, then need to have all MainActivity Fragments update themselves
                        }
                    }
                    ZeoCompanionApplication.mZeoAppHandler.mZeoApp_Prior_State = ZeoAppHandler.ZAH_ZEOAPP_STATE_UNKNOWN;    // prevent accidental double-trigger
                }

                // is there a today daypoint CSE?
                CompanionSleepEpisodesRec sRec2 = getTodayDaypointCSE();
                if (sRec2 != null) {
                    Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: has today daypoint CSE");
                    // if there is a today daypoint CSE then the Zeo App likely never got to Recording
                    long mapOfContent = sRec2.getContentsBitmap();
                    Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: today CSE contents=0x"+String.format("%X", mapOfContent));
                    if ((mapOfContent & 0xFFFFFFFE) == 0 || (mapOfContent & 0xFFFF60FE) == 0) {
                        // has no attributes and is unlinked, and has no events; OR
                        // has no attributes and is unlinked, and if it has any events, there is just one that is Zeo_Starting;
                        // do not need to keep this record
                        Log.d(_CTAG + ".zeoAppChg", "Change to Idle or Ending: meets criteria for record deletion and removal from today daypoint");
                        if (sRec2.rID > 0) {
                            CompanionSleepEpisodesRec.removeFromDB(ZeoCompanionApplication.mDatabaseHandler, sRec2.rID);
                        }
                        sRec2.destroy();
                        mDaypoint_CSEs[1] = null;
                        if (mDaypoint == 0) { sendDoAllUpdateMsgToUI(); }  // if today daypoint is showing, then need to have all MainActivity Fragments update themselves
                    }
                }
                break;
        }
    }

    // Thread context: Main Thread
    // callback from the ZeoApp Handler; no state change occurred
    public void onZeoAppProbedSameState() {
        switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                // capture the max and especially min battery voltages while recording
                if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID != 0) {
                    CompanionSleepEpisodesRec sRec = getTodayDaypointCSE();
                    if (sRec != null) {
                        if (sRec.rZeoHeadbandBattery_High != ZeoCompanionApplication.mZeoAppHandler.mZeoHeadband_battery_maxWhileRecording ||
                                sRec.rZeoHeadbandBattery_Low != ZeoCompanionApplication.mZeoAppHandler.mZeoHeadband_battery_minWhileRecording) {
                            sRec.rZeoHeadbandBattery_High = ZeoCompanionApplication.mZeoAppHandler.mZeoHeadband_battery_maxWhileRecording;
                            sRec.rZeoHeadbandBattery_Low = ZeoCompanionApplication.mZeoAppHandler.mZeoHeadband_battery_minWhileRecording;
                            sRec.saveToDB();
                        }
                    }
                }
                break;
        }
    }

    // sync the Today daypoint's existing or newly allocated CSE with the now active ZeoApp Record;
    // this will handle RECORDING and ENDING; it can handle STARTING but generally that state does not have a SleepEpisode_ID assigned yet
    private void syncTodayDaypointToZeoRecord(boolean isAChangeState, boolean doInjectZeoEvent) {
        Log.d(_CTAG+".syncDaytoZeo","=====>INVOKED");
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State <= ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE) { return; }
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID == 0) { return; }

        // do we have this ZSE linked in any daypoint?
        int in_daypoint = -2;
        if (mDaypoint_CSEs[0] != null) {
            if (mDaypoint_CSEs[0].rZeoSleepEpisode_ID == ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID) { in_daypoint = -1; }
        }
        if (in_daypoint == -2 && mDaypoint_CSEs[1] != null) {
            if (mDaypoint_CSEs[1].rZeoSleepEpisode_ID == ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID) { in_daypoint = 0; }
        }
        if (in_daypoint == -2 && mDaypoint_CSEs[2] != null) {
            if (mDaypoint_CSEs[2].rZeoSleepEpisode_ID == ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID) { in_daypoint = 1; }
        }
        if (in_daypoint > -2) {
            // it has been found in a daypoint, is it Today?
            if (in_daypoint == 0) {
                // yes, everything is okay
            } else if (in_daypoint == -1) {
                // the ZSE is in the yesterday daypoint
                switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                        if (isAChangeState) {
                            // is a change to Starting or Recording;
                            // this occurs when user with headband on walks out-of-range of the Android device, Zeo App terminates the sleep record and goes Idle;
                            // but when the user walks back within range, the Zeo App "un-terminates" the sleep record and goes back to recording
                            Log.d(_CTAG+".syncDaytoZeo","Unusual: CSE linked to ZSE was found in Yesterday slot but ZSE just changed to recording; re-slotting to Today and forcing CSE back into active state");
                            if (mDaypoint_CSEs[1] == null) {
                                mDaypoint_CSEs[1] = mDaypoint_CSEs[0];
                                mDaypoint_CSEs[0] = null;
                            } else {
                                if (mDaypoint_CSEs[2] != null) { mDaypoint_CSEs[2].destroy(); }
                                mDaypoint_CSEs[2] = mDaypoint_CSEs[1];
                                mDaypoint_CSEs[1] = mDaypoint_CSEs[0];
                                mDaypoint_CSEs[0] = null;
                            }
                            mDaypoint = 0;
                            mDaypoint_CSEs[1].rStatesFlag = (mDaypoint_CSEs[1].rStatesFlag & ~CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITEND);
                            mDaypoint_CSEs[1].rZeoEventEnding_Timestamp = 0L;
                            sendDoAllUpdateMsgToUI();
                        } else {
                            // have been recording all along; this is acceptable as it occurs when end-user presses DONE_SLEEPING button
                            syncCSEtoZeoRecord(mDaypoint_CSEs[0], isAChangeState, doInjectZeoEvent);
                            return;
                        }
                        break;

                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                        // either changing to Ending or already changed to Ending
                        // this is acceptable for Zeo App at Ending
                        syncCSEtoZeoRecord(mDaypoint_CSEs[0], isAChangeState, doInjectZeoEvent);
                        return;
                }
            } else {
                // its in the tomorrow daypoint; so make it the today daypoint
                Log.d(_CTAG+".syncDaytoZeo","Unusual: CSE linked to ZSE was found in Tomorrow slot; forcing it to Today");
                if (mDaypoint_CSEs[0] != null) { mDaypoint_CSEs[0].destroy(); }
                mDaypoint_CSEs[0] = mDaypoint_CSEs[1];
                mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                mDaypoint_CSEs[2] = null;
                sendDoAllUpdateMsgToUI();
            }
            // the Today daypoint is all set for getting into sync
            syncCSEtoZeoRecord(mDaypoint_CSEs[1], isAChangeState, doInjectZeoEvent);
            return;
        }
        Log.d(_CTAG+".syncDaytoZeo","CSE linked to ZSE is not in the daypoints");

        // ZSE is not linked in any daypoint; has this ZSE ever been linked to a CSE (which perhaps has been marked dead)?
        Cursor cursor = ZeoCompanionApplication.mDatabaseHandler.getAllCompanionSleepEpisodesRecsLinkedToZSEid(ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                // indeed a prior CSE has been found linked with this ZSE ID
                CompanionSleepEpisodesRec sRec = new CompanionSleepEpisodesRec(cursor);
                Log.d(_CTAG+".syncDaytoZeo","Unusual: Located CSE linked to ZSE in database; re-slotting it as today");
                if (mDaypoint_CSEs[1] == null) {
                    mDaypoint_CSEs[1] = sRec;
                } else {
                    if (mDaypoint_CSEs[2] != null) { mDaypoint_CSEs[2].destroy(); }
                    mDaypoint_CSEs[2] = mDaypoint_CSEs[1];
                    mDaypoint_CSEs[1] = sRec;
                }

                // the Today daypoint is all set for getting into sync
                mDaypoint = 0;
                if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State == ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING ||
                        ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State == ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING) {
                    if (isAChangeState) {
                        // is a change to Starting or Recording;
                        // this occurs when user with headband on walks out-of-range of the Android device, Zeo App terminates the sleep record and goes Idle;
                        // but when the user walks back within range, the Zeo App "un-terminates" the sleep record and goes back to recording
                        Log.d(_CTAG+".syncDaytoZeo","Unusual: ZSE is still recording; forcing CSE back into active state");
                        mDaypoint_CSEs[1].rStatesFlag = (mDaypoint_CSEs[1].rStatesFlag & ~CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITEND);
                        mDaypoint_CSEs[1].rZeoEventEnding_Timestamp = 0L;
                    }
                }
                sendDoAllUpdateMsgToUI();
                syncCSEtoZeoRecord(mDaypoint_CSEs[1], isAChangeState, doInjectZeoEvent);
                return;
            }
            cursor.close();
        }
        Log.d(_CTAG+".syncDaytoZeo","ZSE has never been linked before");

        // ZSE ID has never been linked to a prior CSE;
        // need to create a new CSE or possibly use a ready future CSE
        if (mDaypoint_CSEs[1] == null) {
            // the ZSE is not in any of our daypoints, and the today daypoint is empty
            if (mDaypoint_CSEs[2] != null) {
                // there is a future CSE ready
                Log.d(_CTAG+".syncDaytoZeo","Unusual: Reslotting an existing Tomorrow CSE");
                mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                mDaypoint_CSEs[2] = null;
                if (mDaypoint >= 0) { sendDoAllUpdateMsgToUI(); }  // if today or tomorrow daypoint is showing, then need to have all MainActivity Fragments update themselves
            } else {
                // otherwise create a new CSE
                Log.d(_CTAG+".syncDaytoZeo","Create a new Today CSE");
                mDaypoint_CSEs[1] = new CompanionSleepEpisodesRec(System.currentTimeMillis());
                mDaypoint_CSEs[1].rZeoSleepEpisode_ID = ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID;
                if (mDaypoint == 0) { sendDoAllUpdateMsgToUI(); }  // if today daypoint is showing, then need to have all MainActivity Fragments update themselves
            }
        } else if (mDaypoint_CSEs[1].rZeoSleepEpisode_ID == 0) {
            // the ZSE is not in any of our daypoints, and the today daypoint has a CSE and it is waiting to be linked
            Log.d(_CTAG+".syncDaytoZeo","Linking CSE to ZSE");
            mDaypoint_CSEs[1].rZeoSleepEpisode_ID = ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID;
        } else {
            // the ZSE is not in any of our daypoints, and the today daypoint is NOT empty yet linked; so something is out-of-order;
            // just force today into future and create a new today
            Log.d(_CTAG+".syncDaytoZeo","Unusual: Today CSE is not the same ZSE ID as the active ZSE; forcing Today into Tomorrow; creating new Today CSE");
            if (mDaypoint_CSEs[2] != null) { mDaypoint_CSEs[2].destroy(); }
            mDaypoint_CSEs[2] = mDaypoint_CSEs[1];
            mDaypoint_CSEs[1] = new CompanionSleepEpisodesRec(System.currentTimeMillis());
            mDaypoint_CSEs[1].rZeoSleepEpisode_ID = ZeoCompanionApplication.mZeoAppHandler.mZeoApp_active_SleepEpisode_ID;
            if (mDaypoint >= 0) { sendDoAllUpdateMsgToUI(); }  // if yesterday or today daypoint is showing, then need to have all MainActivity Fragments update themselves
        }

        // the Today daypoint is all set for getting into sync
        syncCSEtoZeoRecord(mDaypoint_CSEs[1], isAChangeState, doInjectZeoEvent);
    }

    // sync the specified CompanionSleepEpisodesRec with the ZeoApp status
    private void syncCSEtoZeoRecord(CompanionSleepEpisodesRec theCSE, boolean isAChangeState, boolean doInjectZeoEvent) {
        Log.d(_CTAG+".syncCSEtoZeoRec","=====>INVOKED");
        if (theCSE == null) { return; }
        if (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State <  ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE) { return; }

        switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                Log.d(_CTAG+".syncCSEtoZeoRec","STARTING eventInject="+doInjectZeoEvent);
                if (doInjectZeoEvent) {
                    if (theCSE.mEvents_array != null) {
                        CompanionSleepEpisodeEventsParsedRec evt = new CompanionSleepEpisodeEventsParsedRec(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_INBED,
                                System.currentTimeMillis(), CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_STARTING, "");
                        theCSE.storeEvent(evt);
                    } else {
                        theCSE.rZeoEventStarting_Timestamp = System.currentTimeMillis();
                    }
                }
                // note do not save the record to the database yet
                break;

            case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                Log.d(_CTAG+".syncCSEtoZeoRec","RECORDING flag then eventInject="+doInjectZeoEvent+" then save");
                theCSE.rStatesFlag = (theCSE.rStatesFlag | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITRECORD);
                if (doInjectZeoEvent) {
                    if (theCSE.mEvents_array != null) {
                        CompanionSleepEpisodeEventsParsedRec evt = new CompanionSleepEpisodeEventsParsedRec(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_INBED,
                                System.currentTimeMillis(), CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_RECORDING, "");
                        theCSE.storeEvent(evt);
                    }
                    else { theCSE.rZeoEventRecording_Timestamp = System.currentTimeMillis(); }
                }
                theCSE.saveToDB();
                break;

            case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                Log.d(_CTAG+".syncCSEtoZeoRec","ENDING flag then eventInject="+doInjectZeoEvent+" then (fall-thru)");
                theCSE.rStatesFlag = (theCSE.rStatesFlag | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_EXPLICITEND);
                if (doInjectZeoEvent) {
                    if (theCSE.mEvents_array != null) {
                        CompanionSleepEpisodeEventsParsedRec evt = new CompanionSleepEpisodeEventsParsedRec(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER,
                                System.currentTimeMillis(), CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_ENDING, "");
                        theCSE.storeEvent(evt);
                    }
                    else { theCSE.rZeoEventEnding_Timestamp = System.currentTimeMillis(); }
                }
                // allow to "fall-thru"
            case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
                Log.d(_CTAG+".syncCSEtoZeoRec","ENDING/IDLE test-if-zeo-dead then save");
                if (isAChangeState) {
                    IntegratedHistoryRec iRec = buildIntegratedHistoryRec(theCSE);
                    if (iRec != null) {
                        if (iRec.theZAH_SleepRecord != null) {
                            if (iRec.theZAH_SleepRecord.rTime_Total_Z_min > 0.0) {
                                amendTheSleepRecord(iRec, true);
                                theCSE.rStatesFlag = (theCSE.rStatesFlag & ~CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC);
                            } else {
                                theCSE.rStatesFlag = (theCSE.rStatesFlag | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_ZEO_DEADREC);
                            }
                        }
                        iRec.theCSErecord = null;
                        iRec.destroy();
                    }
                }
                theCSE.saveToDB();
                break;
        }
    }

    /////////////////////////////////////////////////////////////////////////
    // Methods related to management of the Daypoint
    /////////////////////////////////////////////////////////////////////////

    // return the Journal's current daypoint number
    public int getJournalDaypoint() { return mDaypoint; }

    // get a string for what should be shown next to the Tomorrow button according to the current Daypoint
    public String getTomorrowDaypointStateString() {
        switch (mDaypoint) {
            case -1:
                return getTodayDaypointStatus_internal()+"\n"+getDaypointTimes_internal(1);
            case 0:
                if (mDaypoint_CSEs[2] == null) { return ""; }
                return "Next\n"+getDaypointTimes_internal(2);
            case 1:
                return "";  // we are at the Tomorrow Daypoint, so there is nothing to show here
        }
        return "Unknown";
    }

    // get a string for what should be shown in the "Current" middle slot according to the current Daypoint
    public String getTodayDaypointString() {
        switch (mDaypoint) {
            case -1:
                if (mDaypoint_CSEs[0] == null) { return "None"; }
                return "Prior\n"+getDaypointTimes_internal(0);
            case 0:
                return getTodayDaypointStatus_internal()+"\n"+getDaypointTimes_internal(1);
            case 1:
                if (mDaypoint_CSEs[2] == null) { return ""; }   // do not show option for NEXT
                return "Next\n"+getDaypointTimes_internal(2);
        }
        return "Unknown";
    }

    // get a string for what should be shown next to the Yesterday button according to the current Daypoint
    public String getYesterdayDaypointStateString() {
        switch (mDaypoint) {
            case -1:
                return "";  // we are at the Yesterday Daypoint, so there is nothing to show here
            case 0:
                if (mDaypoint_CSEs[0] == null) { return "None"; }
                return "Prior\n"+getDaypointTimes_internal(0);
            case 1:
                return getTodayDaypointStatus_internal()+"\n"+getDaypointTimes_internal(1);
        }
        return "Unknown";
    }

    // get a string that indicates the Zeo App's status according to the current Daypoint
    public String getZeoDaypointStateString() {
        switch (mDaypoint) {
            case -1:
                CompanionSleepEpisodesRec activeCSE = getDaypointCSE();
                if (activeCSE != null) {
                    if (activeCSE.rZeoSleepEpisode_ID != 0) { return ZeoCompanionApplication.mZeoAppHandler.getStateStringOfID(activeCSE.rZeoSleepEpisode_ID); }
                }
                return "None";
            case 0:
                return ZeoCompanionApplication.mZeoAppHandler.getStateString();
            case 1:
                return "None";
        }
        return "Unknown";
    }

    // get a string that indicates the Journal's status according to the current Daypoint
    public String getJournalDaypointStateString() {
        if (mDaypoint_CSEs[mDaypoint+1] == null) { return "None"; }
        String str = "";
        if (mDaypoint_CSEs[mDaypoint+1].rZeoSleepEpisode_ID != 0) { str = str + "Linked & "; }
        else { str = str + "Unlinked & "; }
        str = str + (mDaypoint_CSEs[mDaypoint+1].getStatusString());
        return str;
    }

    // get a string that indicates the Journal's status according to the current Daypoint
    public int getJournalDaypointStateCode() {
        if (mDaypoint_CSEs[mDaypoint+1] == null) { return -1; }
        return (mDaypoint_CSEs[mDaypoint+1].getStatusCode());
    }

    // generates a overday Journal status for only "Today" daypoint
    private String getTodayDaypointStatus_internal() {
        if (mDaypoint_CSEs[1] == null) {
            // there is no "Today" daypoint CSE record, so just examine the Zeo App's status
            switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
                case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
                    return "Tonight";
                case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                    return "Starting";
                case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                    return "Recording";
                case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                    return "Ending";
                default:
                    return "Tonight";
            }
        } else {
            // there is a "Today" CSE, get its status code
            int code = mDaypoint_CSEs[1].getStatusCode();
            if (code == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_STATUSCODE_EMPTY ||
                    code == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_STATUSCODE_JUSTLINKED) {
                // the CSE's status is empty, or empty-but-linked, so just examine the Zeo App's status
                switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
                        return "Tonight";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                        return "Starting";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                        return "Recording";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                        return "Ending";
                    default:
                        return "Tonight";
                }
            } else if (code == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_STATUSCODE_RECORDING) {
                // the CSE's status is Recording
                switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
                        return "Waiting";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                        return "Syncing";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                        return "Recording";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                        return "Ending";
                    default:
                        return "Recording Mis-Sync";
                }
            } else {
                // the CSE's status is Done
                switch (ZeoCompanionApplication.mZeoAppHandler.mZeoApp_State) {
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_IDLE:
                        return "Done";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_STARTING:
                        return "Mis-Sync";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_RECORDING:
                        return "Still Recording";
                    case ZeoAppHandler.ZAH_ZEOAPP_STATE_ENDING:
                        return "Ending";
                    default:
                        return "Done";
                }
            }
        }
    }

    // generates a date range string for a CSE in a particualar daypoint slot
    private String getDaypointTimes_internal(int pos) {
        CompanionSleepEpisodesRec sRec = mDaypoint_CSEs[pos];
        if (sRec == null) { return ""; };
        Date dt1 = new Date(sRec.rStartOfRecord_Timestamp);
        String str = mJSB_sdf1.format(dt1);
        if (sRec.rEndOfRecord_Timestamp != 0) {
            Date dt2 = new Date(sRec.rEndOfRecord_Timestamp);
            str = str + "-" + mJSB_sdf2.format(dt2);
        } else if (sRec.rZeoEventEnding_Timestamp != 0) {
            Date dt2 = new Date(sRec.rZeoEventEnding_Timestamp);
            str = str + "-" + mJSB_sdf2.format(dt2);
        }
        return str;
    }

    // called by the Journal Fragments to know whether each one is enabled or not according to the current Daypoint
    public String isFragmentDaypointEnabled(int sleepStage) {
        switch (sleepStage) {
            case CompanionDatabaseContract.SLEEP_EPISODE_STAGE_BEFORE:
                switch (mDaypoint) {
                    case -1:
                        if (mDaypoint_CSEs[0] == null) { return "No immediately prior sleep session is available"; }
                        return null;
                    case 0:
                        return null;
                    case 1:
                        return "Entry of Before Attributes for future sleep sessions is not allowed";
                }
                break;
            case CompanionDatabaseContract.SLEEP_EPISODE_STAGE_INBED:
            case CompanionDatabaseContract.SLEEP_EPISODE_STAGE_GOING:
            case CompanionDatabaseContract.SLEEP_EPISODE_STAGE_DURING:
                switch (mDaypoint) {
                    case -1:
                        if (mDaypoint_CSEs[0] == null) { return "No immediately prior sleep session is available"; }
                        return "Entry of Events for past sleep sessions is not allowed";
                    case 0:
                        return null;
                    case 1:
                        return "Entry of Going or During Events for future sleep sessions is not allowed";
                }
                break;
            case CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER:
                switch (mDaypoint) {
                    case -1:
                        if (mDaypoint_CSEs[0] == null) { return "No immediately prior sleep session is available"; }
                        return null;
                    case 0:
                        return null;
                    case 1:
                        return "Entry of After Events or Attributes for future sleep sessions is not allowed";
                }
                break;
        }
        return null;
    }

    // called only by the Attributes Fragment in AFTER sleep stage, the JDC indicates whether the Done Sleeping button should be shown or not
    public boolean isFragmentDaypointButtonDoneSleepingEnabled() {
        switch (mDaypoint) {
            case -1:
                if (mDaypoint_CSEs[0] == null) { return false; }
                return false;
            case 0:
                return true;
            case 1:
                return false;
        }
        return true;
    }

    // the Journal Status Bar fragment's Yesterday/Last/Previous button has been pressed
    public void daypointYesterdayButtonPressed() {
        if (mDaypoint > -1) { mDaypoint--; }
        sendDoAllUpdateMsgToUI();
    }

    // the Journal Status Bar fragment's Tomorrow/Next button has been pressed
    public void daypointTomorrowButtonPressed() {
        if (mDaypoint < 0) { mDaypoint++; }
        sendDoAllUpdateMsgToUI();
    }

    // when a sleep session has ended (either by Zeo or by Journal) then change the Daypoint to Yesterday, and
    // move the Today CSE as the Yesterday CSE
    private void resetDaypointForNewSleepSession() {
        if (mDaypoint_CSEs[0] != null) { mDaypoint_CSEs[0].destroy(); }
        mDaypoint_CSEs[0] = mDaypoint_CSEs[1];
        mDaypoint = -1;
        mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
        mDaypoint_CSEs[2] = null;
        sendDoAllUpdateMsgToUI();
    }

    // get the CSE that the Daypoint is currently pointing; can be null
    private CompanionSleepEpisodesRec getDaypointCSE() { return mDaypoint_CSEs[mDaypoint+1]; }

    // get the CSE of the Today Daypoint; can be null
    private CompanionSleepEpisodesRec getTodayDaypointCSE() { return mDaypoint_CSEs[1]; }

    // get the CSE of the Yesterday Daypoint; can be null
    private CompanionSleepEpisodesRec getYesterdayDaypointCSE() { return mDaypoint_CSEs[0]; }

    // get the CSE that the Daypoint is currently pointing to (and create it if it is null);
    // this is called by attribute and event add/delete handlers
    private CompanionSleepEpisodesRec getDaypointCSECreateIfShould() {
        if (mDaypoint_CSEs[mDaypoint+1] == null) {
            if (mDaypoint != 0) {
                // create a CSE for yesterday or tomorrow
                mDaypoint_CSEs[mDaypoint+1] = createNewCSE_internal();
            } else {
                // create a CSE for today
                if (mDaypoint_CSEs[2] != null) {
                    // there is a Tomorrow CSE stored away, so use it; this should rarely if never happen
                    mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                    mDaypoint_CSEs[2] = null;
                } else { mDaypoint_CSEs[1] = createNewCSE_internal(); }
            }
        }
        return mDaypoint_CSEs[mDaypoint+1];
    }

    // get the CSE for the Today Daypoint (and create it if it is null);
    // this is called when the Zeo App state changes
    private CompanionSleepEpisodesRec getTodayDaypointCSECreateIfShould() {
        if (mDaypoint_CSEs[1] == null) {
            if (mDaypoint_CSEs[2] != null) {
                // there is a Tomorrow CSE stored away, so use it; this should rarely if never happen
                mDaypoint_CSEs[1] = mDaypoint_CSEs[2];
                mDaypoint_CSEs[2] = null;
            } else { mDaypoint_CSEs[1] = createNewCSE_internal(); }

        }
        return mDaypoint_CSEs[1];
    }

    // get the CSE in one of the three Daypoints that is for the specified Zeo Sleep Episode ID
    private int getDaypointWithZeoID(long id) {
        for (int i = -1; i <= 1; i++) {
            if (mDaypoint_CSEs[i+1] != null) {
                if (mDaypoint_CSEs[i + 1].rZeoSleepEpisode_ID == id) { return i; }
            }
        }
        return -2;
    }

    /////////////////////////////////////////////////////////////////////////
    // CSE management methods; creating, deleting, adding events, managing attribute adds/deletes
    /////////////////////////////////////////////////////////////////////////

    // create or obtain a new CDE for a Daypoint slot
    private CompanionSleepEpisodesRec createNewCSE_internal() {
        CompanionSleepEpisodesRec newCSE = new CompanionSleepEpisodesRec(System.currentTimeMillis());
        sendDoJSBupdateMsgToUI();
        return newCSE;
    }

    // the end-user choose to delete a CSE which may be in the Daypoint and shown on the JSB
    public void informCSEdeleted(long cse_id) {
        for (int i = -1; i <= 1; i++) {
            if (mDaypoint_CSEs[i+1] != null) {
                if (mDaypoint_CSEs[i+1].rID == cse_id) {
                    // indeed this is in the Daypoint; remove it, resynchronize, then have the JSB update itself
                    mDaypoint_CSEs[i+1].destroy();
                    mDaypoint_CSEs[i+1] = null;
                    resynchronize();
                    sendDoJSBupdateMsgToUI();
                }
            }
        }
    }

    // record an event within the Daypoint; will return false if the Daypoint will not allow a new event
    public boolean recordDaypointEvent(int sleepStage, int eventNo, String info) {
        Log.d(_CTAG+".recEvent","=====>EVENT="+eventNo);
        CompanionSleepEpisodesRec activeCSE = getDaypointCSECreateIfShould();
        if (activeCSE == null) { return false; }
        CompanionSleepEpisodeEventsParsedRec newRec = new CompanionSleepEpisodeEventsParsedRec(sleepStage, System.currentTimeMillis(), eventNo, info);
        if (activeCSE.isNearDuplicateBeforeStoring(newRec)) {
            //Log.d(_CTAG+".recEvent","Event rejected as duplicate");
            return false;
        }
        activeCSE.storeEvent(newRec);

        // additional actions for specific events
        if (eventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_GOT_INTO_BED ||
            eventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_GOING_TO_SLEEP) {
                activeCSE.rStatesFlag = (activeCSE.rStatesFlag | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_JOURNAL_EXPLICITSTART);
        }
        if (eventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_DONE_SLEEPING) {
            activeCSE.rStatesFlag = (activeCSE.rStatesFlag | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_STATESFLAG_JOURNAL_EXPLICITEND);
            activeCSE.rEndOfRecord_Timestamp = newRec.rTimestamp;
            IntegratedHistoryRec iRec = buildIntegratedHistoryRec(activeCSE);
            if (iRec != null) {
                if (iRec.theZAH_SleepRecord != null) {
                    amendTheSleepRecord(iRec, true);
                }
                iRec.theCSErecord = null;
                iRec.destroy();
            }
            if (mDaypoint == 0) { resetDaypointForNewSleepSession(); }
        }
        activeCSE.saveToDB();
        sendDoJSBupdateMsgToUI();
        ZeoCompanionApplication.mZeoAppHandler.probing_OnEvent(eventNo);
        return true;
    }

    // record an attribute/value pair within the Daypoint; will return false if the Daypoint will not allow a new attribute/value pair
    public boolean recordDaypointAttributeValueOfType(int sleepStage, CompanionAttributesRec typeRec, String value, float likert) {
        CompanionSleepEpisodesRec activeCSE = getDaypointCSECreateIfShould();
        if (activeCSE == null) { return false; }
        activeCSE.storeAttributeValue(sleepStage, typeRec, value, likert);
        if (typeRec.rAppliesToStage == CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER) {
            activeCSE.rEndOfRecord_Timestamp = System.currentTimeMillis();
        }
        activeCSE.saveToDB();
        ZeoCompanionApplication.mZeoAppHandler.probing_OnAttribute();
        return true;
    }

    // remove an attribute/value pair within the Daypoint; will return false if the Daypoint will not allow a the removal of an attribute/value pair
    public boolean removeDaypointInfoAttributeValueOfType(int sleepStage, CompanionAttributesRec typeRec) {
        CompanionSleepEpisodesRec activeCSE = getDaypointCSE();
        if (activeCSE == null) { return false; }
        activeCSE.removeAttribute(sleepStage, typeRec);
        activeCSE.saveToDB();
        ZeoCompanionApplication.mZeoAppHandler.probing_OnAttribute();
        return true;
    }

    // get the value for an attribute within the Daypoint; will return NULL if there is no CSE for that Daypoint
    public String getDaypointCurrentInfoAttributeValue(int sleepStage, CompanionAttributesRec typeRec) {
        CompanionSleepEpisodesRec activeCSE = getDaypointCSE();
        if (activeCSE == null) { return null; }
        return activeCSE.getExistingAtrributeValue(sleepStage, typeRec);
    }

    // create a summary for the current Daypoint
    public void createDaypointSummaryList(ArrayList<String> theArray) {
        CompanionSleepEpisodesRec activeCSE = getDaypointCSE();
        if (activeCSE == null) {
            theArray.add("No Sleep Record created yet");
            return;
        }
        createSummaryList(activeCSE, theArray);
    }

    // create a summary for the supplied CSE record
    public void createSummaryList(CompanionSleepEpisodesRec theCSE, ArrayList<String> theArray) {
        try {   // master Exception catcher
            theArray.clear();
            String str = "Sleep Journal-ID# ";
            if (theCSE.rID > 0) { str = str  + theCSE.rID; }
            else { str = str + "(not yet saved)"; }
            if (theCSE.rZeoSleepEpisode_ID != 0) {
                str = str + " linked to Zeo-ID# " + theCSE.rZeoSleepEpisode_ID;
                boolean isChecked = ((theCSE.rAmendedFlags & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_CHECKED) != 0);
                boolean isAmended = ((theCSE.rAmendedFlags & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_AMENDED) != 0);
                if (isAmended) { str = str + " amended"; }
                else if (isChecked) { str = str + " checked not amended"; }
                else { str = str + " not checked"; }
            }
            theArray.add(str);

            if (theCSE.rZeoHeadbandBattery_High > 0) {
                theArray.add("Battery Highest: "+theCSE.rZeoHeadbandBattery_High);
            }
            if (theCSE.rZeoHeadbandBattery_Low > 0) {
                theArray.add("Battery Lowest: "+theCSE.rZeoHeadbandBattery_Low);
            }

            theCSE.unpackEventCSVstring();
            theCSE.unpackInfoCSVstrings();

            if (theCSE.mEvents_array == null && theCSE.mAttribs_Vari_array == null && theCSE.mAttribs_Fixed_array == null) {
                theArray.add("Nothing recorded yet");
                return;
            }

            String sleepStageStr = CompanionDatabaseContract.getSleepStageString(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_BEFORE);
            if (theCSE.mAttribs_Fixed_array != null) {
                if (!theCSE.mAttribs_Fixed_array.isEmpty()) {
                    for (CompanionSleepEpisodeInfoParsedRec existing1 : theCSE.mAttribs_Fixed_array) {
                        if (existing1 != null) {
                            if (existing1.rSleepStage == CompanionDatabaseContract.SLEEP_EPISODE_STAGE_BEFORE) {
                                theArray.add(sleepStageStr + ": " + existing1.getSummaryString());
                            }
                        }
                    }
                }
            }

            if (theCSE.mAttribs_Vari_array != null) {
                if (!theCSE.mAttribs_Vari_array.isEmpty()) {
                    for (CompanionSleepEpisodeInfoParsedRec existing2 : theCSE.mAttribs_Vari_array) {
                        if (existing2.rSleepStage == CompanionDatabaseContract.SLEEP_EPISODE_STAGE_BEFORE) {
                            theArray.add(sleepStageStr + ": " + existing2.getSummaryString());
                        }
                    }
                }
            }

            if (theCSE.mEvents_array != null) {
                if (!theCSE.mEvents_array.isEmpty()) {
                    for (CompanionSleepEpisodeEventsParsedRec existing3 : theCSE.mEvents_array) {
                        theArray.add("\t" + existing3.getSummaryString());
                    }
                }
            }

            sleepStageStr = CompanionDatabaseContract.getSleepStageString(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER);
            if (theCSE.mAttribs_Fixed_array != null) {
                if (!theCSE.mAttribs_Fixed_array.isEmpty()) {
                    for (CompanionSleepEpisodeInfoParsedRec existing1 : theCSE.mAttribs_Fixed_array) {
                        if (existing1 != null) {
                            if (existing1.rSleepStage == CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER) {
                                theArray.add(sleepStageStr + ": " + existing1.getSummaryString());
                            }
                        }
                    }
                }
            }

            if (theCSE.mAttribs_Vari_array != null) {
                if (!theCSE.mAttribs_Vari_array.isEmpty()) {
                    for (CompanionSleepEpisodeInfoParsedRec existing2 : theCSE.mAttribs_Vari_array) {
                        if (existing2.rSleepStage == CompanionDatabaseContract.SLEEP_EPISODE_STAGE_AFTER) {
                            theArray.add(sleepStageStr + ": " + existing2.getSummaryString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            ZeoCompanionApplication.postToErrorLog(_CTAG+".createSummaryList", e, "For CSE ID="+theCSE.rZeoSleepEpisode_ID);    // automatically posts a Log.e
        }
    }

    /////////////////////////////////////////////////////////////////////////
    // Methods related to creating and managing integrated History Records
    /////////////////////////////////////////////////////////////////////////

    // this class defines an Integrated History Record that combine ZeoCompanion Sleep information with the Zeo App's cooresponding Sleep information
    public class IntegratedHistoryRec {
        public long mTimestamp = 0;
        public long mCSEid = 0;
        public long mZSEid = 0;
        public int mFound = 0;
        public ZAH_SleepRecord theZAH_SleepRecord = null;
        public CompanionSleepEpisodesRec theCSErecord = null;
        public Bitmap theHypnogramBitmap = null;

        public void destroy() {
            // although highly disputed: assist garbage collection since this class is a holder of other large and nested class instances;
            // running memory profiles of the App clearly demonstrates the need for this in this highly particular instance
            if (theZAH_SleepRecord != null) { theZAH_SleepRecord.destroy(); theZAH_SleepRecord = null; }
            if (theCSErecord != null) { theCSErecord.destroy(); theCSErecord = null; }
            if (theHypnogramBitmap != null) { theHypnogramBitmap.recycle(); theHypnogramBitmap = null; }
        }
    }

    // create an empty CSE within an iRec that does not have one; usually this is for setting flags for a Zeo App Sleep Session that is not using the Sleep Journal
    public void createCSEforIrec(IntegratedHistoryRec iRec) {
        if (iRec == null) { return; }
        if (iRec.theCSErecord != null) { return; }
        iRec.theCSErecord = new CompanionSleepEpisodesRec(System.currentTimeMillis());
        iRec.mCSEid = iRec.theCSErecord.rID;
        iRec.mFound |= 0x01;
        if (iRec.theZAH_SleepRecord != null) {
            iRec.theCSErecord.rZeoSleepEpisode_ID = iRec.theZAH_SleepRecord.rSleepEpisodeID;
            iRec.theCSErecord.rStartOfRecord_Timestamp = iRec.theZAH_SleepRecord.rStartOfNight;
        }
        iRec.theCSErecord.saveToDB();
    }

    // get all integrated Sleep Records from both ZeoApp and ZeoCompanion databases; sorted newest to oldest
    public void getAllIntegratedHistoryRecs(ArrayList<IntegratedHistoryRec> theArray) {
        getAllIntegratedHistoryRecs_Internal(theArray, 0, false, false);
    }

    // get all integrated Sleep Records from both ZeoApp and ZeoCompanion databases; sorted newest to oldest
    public void getAllIntegratedHistoryRecsFromDate(ArrayList<IntegratedHistoryRec> theArray, Date fromWhen) {
        long afterTimestamp = 0;
        if (fromWhen != null) { afterTimestamp = fromWhen.getTime(); }
        getAllIntegratedHistoryRecs_Internal(theArray, afterTimestamp, false, false);
    }

    // get all integrated Sleep Records from both ZeoApp and ZeoCompanion databases; sorted newest to oldest
    public void getAllIntegratedHistoryRecsFromDate(ArrayList<IntegratedHistoryRec> theArray, long fromWhen) {
        getAllIntegratedHistoryRecs_Internal(theArray, fromWhen, false, false);
    }

    // get only Zeo Sleep Records after the specified timestamp (used by the CSV Exporter); sorted newest to oldest
    public void getAllZeoRecsFromDate(ArrayList<IntegratedHistoryRec> theArray, Date fromWhen, boolean includeZeoDead) {
        long afterTimestamp = 0;
        if (fromWhen != null) { afterTimestamp = fromWhen.getTime(); }
        getAllIntegratedHistoryRecs_Internal(theArray, afterTimestamp, true, includeZeoDead);
    }

    // internal method that performs the actual queries and integrations; sorted newest to oldest
    private void getAllIntegratedHistoryRecs_Internal(ArrayList<IntegratedHistoryRec> theArray, long afterTimestamp, boolean zeoOnly, boolean includeZeoDead) {
        int journalCnt = 0;
        int zeoCnt = 0;
        theArray.clear();

        // first get all desired sleep episode records from the sleep journal
        if (!zeoOnly) {
            Cursor cursor1 = null;
            if (afterTimestamp > 0) { cursor1 = ZeoCompanionApplication.mDatabaseHandler.getAllCompanionSleepEpisodesRecsAfterDate(afterTimestamp); }
            else { cursor1 = ZeoCompanionApplication.mDatabaseHandler.getAllCompanionSleepEpisodesRecs(); }
            if (cursor1 != null) {
                if (cursor1.moveToFirst()) {
                    do {
                        CompanionSleepEpisodesRec sRec1 = new CompanionSleepEpisodesRec(cursor1);
                        if (!sRec1.isUnlinkedAndDead()) {
                            IntegratedHistoryRec iRec1 = new IntegratedHistoryRec();
                            iRec1.theZAH_SleepRecord = null;
                            iRec1.theCSErecord = sRec1;
                            iRec1.mTimestamp = sRec1.rStartOfRecord_Timestamp;
                            iRec1.mCSEid = sRec1.rID;
                            iRec1.mZSEid = sRec1.rZeoSleepEpisode_ID;
                            iRec1.mFound = 0x01;
                            theArray.add(iRec1);
                            journalCnt++;
                        }
                    } while (cursor1.moveToNext());
                }
                cursor1.close();
            }
        }

        // next merge in all the desired Zeo Sleep Records; two passes to draw from both the Zeo App's database and any replication in the ZeoCompanion database
        for (int pass = 0; pass <= 1; pass++) {
            Cursor cursor2 = null;
            if (pass == 1) { cursor2 = ZeoCompanionApplication.mDatabaseHandler.getAllZeoSleepRecsAfterDate(afterTimestamp); }
            else { cursor2 = ZeoCompanionApplication.mZeoAppHandler.getAllSleepRecsAfterDate(afterTimestamp); }

            if (cursor2 != null) {
                if (cursor2.moveToFirst()) {
                    do {
                        ZAH_SleepRecord zRec1 = new ZAH_SleepRecord(cursor2);
                        if (zeoOnly) {
                            // Zeo records only; no integrating needed
                            if (includeZeoDead || zRec1.rTime_Total_Z_min > 0.0) {
                                boolean found = false;
                                if (pass == 1) {
                                    for (IntegratedHistoryRec existingIrec: theArray) {
                                        if (existingIrec.mZSEid == zRec1.rSleepEpisodeID) {
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                                if (!found) {
                                    IntegratedHistoryRec iRec2 = new IntegratedHistoryRec();
                                    iRec2.theCSErecord = null;
                                    iRec2.theZAH_SleepRecord = zRec1;
                                    iRec2.mTimestamp = zRec1.rStartOfNight;
                                    iRec2.mZSEid = zRec1.rSleepEpisodeID;
                                    iRec2.mFound = 0x02;
                                    theArray.add(iRec2);
                                    zeoCnt++;
                                }
                            }
                        } else {
                            // integration is necessary
                            boolean found = false;
                            for (IntegratedHistoryRec existingIrec: theArray) {
                                if (existingIrec.mZSEid == zRec1.rSleepEpisodeID) {
                                    // found the matching CSE record
                                    if (pass == 0 || (existingIrec.mFound & 0x02) == 0) {
                                        if (includeZeoDead || zRec1.rTime_Total_Z_min > 0.0 || zRec1.rEndReason == ZeoDataContract.SleepRecord.END_REASON_ACTIVE) {
                                            // is a normal active or finished Zeo record or we want dead ZEO records; integrate it with its matching CSE
                                            existingIrec.theZAH_SleepRecord = zRec1;
                                            if (zRec1.rStartOfNight < existingIrec.mTimestamp) { existingIrec.mTimestamp = zRec1.rStartOfNight; }
                                            existingIrec.mZSEid = zRec1.rSleepEpisodeID;
                                            existingIrec.mFound = (existingIrec.mFound | 0x02);
                                            found = true;
                                        } else {
                                            // this is a hidden Zeo record usually because it is incomplete or had no actual sleep
                                            if (existingIrec.theCSErecord != null) {
                                                // it has a matching CSE
                                                if (existingIrec.theCSErecord.isZeoOnly()) {
                                                    // the CSE record has Zeo-only content and the Zeo record would normally be hidden, so eliminate the entire integrated record
                                                    //Log.d(_CTAG+".getIrecs","Removing CSE#"+existingIrec.mCSEid+" with ZSE#"+existingIrec.mZSEid);
                                                    theArray.remove(existingIrec);
                                                    journalCnt--;
                                                } else {
                                                    // the CSE record has content other than Zeo-only content, so do not hide the integrated combination
                                                    existingIrec.theZAH_SleepRecord = zRec1;
                                                    if (zRec1.rStartOfNight < existingIrec.mTimestamp) { existingIrec.mTimestamp = zRec1.rStartOfNight; }
                                                    existingIrec.mZSEid = zRec1.rSleepEpisodeID;
                                                    existingIrec.mFound = (existingIrec.mFound | 0x02);
                                                    found = true;
                                                }
                                            } else {
                                                // not sure how this would happen; preexisting integrated rec without any CSE content and a dead Zeo record
                                                theArray.remove(existingIrec);
                                                //Log.d(_CTAG+".getIrecs","Removing CSE#"+existingIrec.mCSEid+" with ZSE#"+existingIrec.mZSEid);
                                            }
                                        }
                                    }
                                    break;
                                }
                            }
                            if (!found) {
                                // no matching CSE was found, so include it as-is
                                if (includeZeoDead || zRec1.rTime_Total_Z_min > 0.0) {
                                    found = false;
                                    if (pass == 1) {
                                        for (IntegratedHistoryRec existingIrec: theArray) {
                                            if (existingIrec.mZSEid == zRec1.rSleepEpisodeID) {
                                                found = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (!found) {
                                        IntegratedHistoryRec iRec2 = new IntegratedHistoryRec();
                                        iRec2.theCSErecord = null;
                                        iRec2.theZAH_SleepRecord = zRec1;
                                        iRec2.mTimestamp = zRec1.rStartOfNight;
                                        iRec2.mZSEid = zRec1.rSleepEpisodeID;
                                        iRec2.mFound = 0x02;
                                        theArray.add(iRec2);
                                        zeoCnt++;
                                    }
                                }
                            }
                        }
                    } while (cursor2.moveToNext());
                }
                cursor2.close();
            }
        }

        if (journalCnt > 0 && zeoCnt > 0) {
            // because an actual merge occurred, need to sort theArray by descending timestamp
            Collections.sort(theArray, new Comparator<IntegratedHistoryRec>() {
                @Override
                public int compare(IntegratedHistoryRec o1, IntegratedHistoryRec o2) {
                    // configured for DESCENDING sort order
                    if (o1.mTimestamp < o2.mTimestamp) {
                        return 1;
                    }
                    if (o1.mTimestamp > o2.mTimestamp) {
                        return -1;
                    }
                    return 0;
                }
            });
        }
    }

    // reload new copies of the CSE and ZSE in an existing iRec
    public void refreshIntegratedHistoryRec(IntegratedHistoryRec iRec) {
        if (iRec == null) { return; }
        if (iRec.theCSErecord != null && iRec.mCSEid > 0) {
            CompanionSleepEpisodesRec newCSE = ZeoCompanionApplication.mDatabaseHandler.getSpecifiedCompanionSleepEpisodeRecOfID(iRec.mCSEid);
            if (newCSE != null) {
                iRec.theCSErecord.destroy();
                iRec.theCSErecord = null;
                iRec.theCSErecord = newCSE;
            }
        }
        if (iRec.theZAH_SleepRecord != null && iRec.mZSEid > 0) {
            ZAH_SleepRecord newZSE = ZeoCompanionApplication.mZeoAppHandler.getSpecifiedSleepRecOfID(iRec.mZSEid);
            if (newZSE != null) {
                iRec.theZAH_SleepRecord.destroy();
                iRec.theZAH_SleepRecord = null;
                iRec.theZAH_SleepRecord = newZSE;
            }
        }
    }

    // build an IHR given a CSE that has been linked to a ZSE
    public IntegratedHistoryRec buildIntegratedHistoryRec(CompanionSleepEpisodesRec cseRec) {
        if (cseRec.rZeoSleepEpisode_ID == 0) { return null; }

        IntegratedHistoryRec iRec = new IntegratedHistoryRec();
        iRec.mCSEid = cseRec.rID;
        iRec.theCSErecord = cseRec;
        iRec.mTimestamp = cseRec.rStartOfRecord_Timestamp;
        iRec.mZSEid = cseRec.rZeoSleepEpisode_ID;
        iRec.mFound = 0x01;

        iRec.theZAH_SleepRecord = ZeoCompanionApplication.mZeoAppHandler.getSpecifiedSleepRecOfID(iRec.mZSEid);
        if (iRec.theZAH_SleepRecord != null) {
            iRec.mFound = (iRec.mFound | 0x02);
        }
        return iRec;
    }

    /////////////////////////////////////////////////////////////////////////
    // Methods related to amending ZSE records with CSE event information
    /////////////////////////////////////////////////////////////////////////

    // calculate any amendments to the Zeo Sleep Record based upon the Sleep Journal results;  the actual Zeo record is not altered;
    // caller is responisble to save the CSE within the Irec; returns TRUE if changes were made that needs saving (but TRUE does NOT mean the record was amended)
    public boolean amendTheSleepRecord(IntegratedHistoryRec iRec, boolean recheck) {
        class SleepInterval {
            long seqNo = 0;
            long rAwake = 0;
            long rTryingToSleep = 0;
            long rStillAwake = 0;
            long rReAwake = 0;
        }
        boolean wasNotChecked = false;
        boolean amendmentsMade = false;
        Log.d(_CTAG+".amend","=====>INVOKED");

        // do the necessary records even exist?
        if (iRec == null) { return wasNotChecked; }
        if (iRec.theZAH_SleepRecord == null || iRec.theCSErecord == null) { return wasNotChecked; }

        try {
            // can an amendment even be done?
            // already checked and not rechecking?
            if ((iRec.theCSErecord.rAmendedFlags & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_CHECKED) != 0 && !recheck) { return wasNotChecked; }

            // not ready to-be-checked? (and therefore do not want to set the checked flag)
            if (iRec.theZAH_SleepRecord.rEndReason == ZeoDataContract.SleepRecord.END_REASON_ACTIVE) { return wasNotChecked; }
            int code = iRec.theCSErecord.getStatusCode();
            if (code != CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_STATUSCODE_DONE && code != CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_STATUSCODE_SOFTDONE) { return wasNotChecked; }

            // initially ready to-be-checked
            if ((iRec.theCSErecord.rAmendedFlags & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_CHECKED) == 0) {
                iRec.theCSErecord.rAmendedFlags = (iRec.theCSErecord.rAmendedFlags | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_CHECKED);
                wasNotChecked = true;   // set this to true so the caller will save the modified CSE record with the checked flag
            }
            if (iRec.theZAH_SleepRecord.rTime_Total_Z_min == 0L) { return wasNotChecked; }  // Zeo sleep record has no data (is a hidden Zeo record)
            iRec.theCSErecord.unpackEventCSVstring();
            if (iRec.theCSErecord.mEvents_array == null) { return wasNotChecked; }          // no events were record, so nothing to amend
            if (iRec.theCSErecord.mEvents_array.isEmpty()) { return wasNotChecked; }        // no events were record, so nothing to amend

            // yes it can; calculate the timestamp for the Zeo's notion of time to fall asleep
            Log.d(_CTAG+".amend","=====>CONTINUING");
            long zeoStartedSleep = (long)(iRec.theZAH_SleepRecord.rTime_to_Z_min * 60000.0) + iRec.theZAH_SleepRecord.rStartOfNight;
            if (zeoStartedSleep == 0) { zeoStartedSleep = 1000; }

            // backward step through the base hypnogram to locate where the Zeo Headband chose to start accumulating sleep data
            int stage1Needed = (int)(iRec.theZAH_SleepRecord.rTime_Awake_min * 2.0);
            int stage2Needed = (int)(iRec.theZAH_SleepRecord.rTime_REM_min * 2.0);
            int stage3Needed = (int)(iRec.theZAH_SleepRecord.rTime_Light_min * 2.0);
            int stage46Needed = (int)(iRec.theZAH_SleepRecord.rTime_Deep_min * 2.0);
            int stage1 = 0;
            int stage2 = 0;
            int stage3 = 0;
            int stage46 = 0;
            boolean skipTrailingWake = true;
            int inx_baseStartOfSleep = iRec.theZAH_SleepRecord.rBase_Hypnogram.length - 1;
            int m = (int)(iRec.theZAH_SleepRecord.rTime_to_Z_min * 2.0);
            while (inx_baseStartOfSleep >= m) {
                switch (iRec.theZAH_SleepRecord.rBase_Hypnogram[inx_baseStartOfSleep]) {
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                        if (!skipTrailingWake) { stage1++; }
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                        skipTrailingWake = false;
                        stage2++;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                        skipTrailingWake = false;
                        stage3++;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                        skipTrailingWake = false;
                        stage46++;
                        break;
                }
                if (stage1 >= stage1Needed && stage2 >= stage2Needed && stage3 >= stage3Needed && stage46 >= stage46Needed) { break; }
                inx_baseStartOfSleep--;
            }
            Log.d(_CTAG+".amendTheSleepRecord","inx_baseStartOfSleep="+inx_baseStartOfSleep+" instead of "+m);

            // create a "shallow" working copy of the events array so we can add more in proper time sequence without saving them to the database
            ArrayList<CompanionSleepEpisodeEventsParsedRec> extendedEvents = new ArrayList<CompanionSleepEpisodeEventsParsedRec>(iRec.theCSErecord.mEvents_array);

            // augment the Journal's events with SLEEP_EPISODE_EVENT_INJECTED_ZEO_STARTEDSLEEP, and SLEEP_EPISODE_EVENT_INJECTED_ZEO_DEEP_SLEEP
            CompanionSleepEpisodeEventsParsedRec eRec1 = new CompanionSleepEpisodeEventsParsedRec(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_INBED, zeoStartedSleep, CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_INJECTED_ZEO_STARTEDSLEEP, null);
            extendedEvents.add(eRec1);
            boolean deepStarted = false;
            for (int p = 0; p < iRec.theZAH_SleepRecord.rBase_Hypnogram.length; p++) {
                switch (iRec.theZAH_SleepRecord.rBase_Hypnogram[p]) {
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                        if (!deepStarted) {
                            long timestamp = iRec.theZAH_SleepRecord.rStartOfNight + ((long)p * 30000L);
                            eRec1 = new CompanionSleepEpisodeEventsParsedRec(CompanionDatabaseContract.SLEEP_EPISODE_STAGE_DURING, timestamp, CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_INJECTED_ZEO_DEEP_SLEEP, null);
                            extendedEvents.add(eRec1);
                            deepStarted = true;
                        }
                        break;

                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_UNDEFINED:
                        deepStarted = false;
                        break;
                }
            }

            // re-sort into ascending (low to high) timestamp order
            Collections.sort(extendedEvents, new Comparator<CompanionSleepEpisodeEventsParsedRec>() {
                @Override
                public int compare(CompanionSleepEpisodeEventsParsedRec o1, CompanionSleepEpisodeEventsParsedRec o2) {
                    if (o1.rTimestamp > o2.rTimestamp) {
                        return 1;
                    }
                    if (o1.rTimestamp < o2.rTimestamp) {
                        return -1;
                    }
                    return 0;
                }
            });

            // construct a set of sleep intervals based upon the Sleep Journal events;
            boolean gotoNextInterval = false;
            boolean wokeupActive = true;
            ArrayList<SleepInterval> journalSleepIntervals = new ArrayList<SleepInterval>();
            SleepInterval si1 = new SleepInterval();
            si1.rAwake = iRec.theZAH_SleepRecord.rStartOfNight;
            for (CompanionSleepEpisodeEventsParsedRec eRec2: extendedEvents) {
                Log.d(_CTAG+".amend","Processing event="+CompanionSleepEpisodeEventsParsedRec.getEventName(eRec2.rEventNo)+" at relative_min="+((double)((eRec2.rTimestamp-iRec.theZAH_SleepRecord.rStartOfNight)/1000)/60.0));
                switch (eRec2.rEventNo) {
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_NOT_YET_SLEEPING:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_STARTING:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_RECORDING:
                        // ignored
                        break;

                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_INJECTED_ZEO_STARTEDSLEEP:
                        si1.rTryingToSleep = eRec2.rTimestamp;
                        break;

                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_STILL_AWAKE:
                        si1.rStillAwake = eRec2.rTimestamp;
                        break;

                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_INJECTED_ZEO_DEEP_SLEEP:
                        gotoNextInterval = true;
                        wokeupActive = false;
                        break;

                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_GOT_INTO_BED:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_GOING_TO_SLEEP:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_WOKEUP_DID_SOMETHING:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_WOKEUP_RETRY_TO_SLEEP:
                        if (!gotoNextInterval && wokeupActive) {
                            if (eRec2.rEventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_WOKEUP_RETRY_TO_SLEEP ||
                                    eRec2.rEventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_GOING_TO_SLEEP) { wokeupActive = false; }
                            si1.rTryingToSleep = eRec2.rTimestamp;
                            break;
                        }
                        // allow to "fall thru"
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_WOKEUP:
                        if (eRec2.rEventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_WOKEUP) { wokeupActive = true; }
                        si1.rReAwake = eRec2.rTimestamp;
                        journalSleepIntervals.add(si1);
                        si1 = new SleepInterval();
                        si1.rAwake = eRec2.rTimestamp;
                        si1.rTryingToSleep = eRec2.rTimestamp;
                        si1.seqNo = journalSleepIntervals.size();
                        gotoNextInterval = false;
                        break;

                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_ZEO_ENDING:
                    case CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_DONE_SLEEPING:
                        si1.rReAwake = eRec2.rTimestamp;
                        wokeupActive = false;
                        break;
                }
            }

            // clean-up the intervals from the Sleep Journal
            if (si1.rReAwake == 0) { si1.rReAwake = iRec.theZAH_SleepRecord.rEndOfNight; }
            journalSleepIntervals.add(si1);
            //iRec.theCSErecord.rCountAwakenings = journalSleepIntervals.size() - 1;    // not used
            si1 = journalSleepIntervals.get(0);
            if (si1.rAwake == 0) { si1.rAwake = iRec.theZAH_SleepRecord.rStartOfNight; si1.rTryingToSleep = si1.rAwake;  }

            // create copies of both hypnograms
            byte amendedDisplay[] = new byte[iRec.theZAH_SleepRecord.rDisplay_Hypnogram.length];
            System.arraycopy(iRec.theZAH_SleepRecord.rDisplay_Hypnogram, 0, amendedDisplay, 0, iRec.theZAH_SleepRecord.rDisplay_Hypnogram.length);
            byte amendedBase[] = new byte[iRec.theZAH_SleepRecord.rBase_Hypnogram.length];
            System.arraycopy(iRec.theZAH_SleepRecord.rBase_Hypnogram, 0, amendedBase, 0, iRec.theZAH_SleepRecord.rBase_Hypnogram.length);

            // now possibly amend both hypnograms by inserting known absolutely awake intervals (In-bed until Trying-to-sleep, Woke Up to Retrying-to-sleep)
            long startTimestamp = iRec.theZAH_SleepRecord.rStartOfNight;
            if (iRec.theZAH_SleepRecord.mHasExtended && iRec.theZAH_SleepRecord.rDisplayHypnogramStartTime > 0) { startTimestamp = iRec.theZAH_SleepRecord.rDisplayHypnogramStartTime; }

            for (SleepInterval si2: journalSleepIntervals) {
                Log.d(_CTAG+".amend","Span="+si2.seqNo+
                        ", Awake@"+((double)((si2.rAwake-startTimestamp)/1000)/60.0)+
                        ", Trying@"+((double)((si2.rTryingToSleep-startTimestamp)/1000)/60.0)+
                        ", Still@"+((double)((si2.rStillAwake-startTimestamp)/1000)/60.0)+
                        ", ReAwake@"+((double)((si2.rReAwake-startTimestamp)/1000)/60.0));

                if (amendedDisplay.length > 0) {
                    if (si2.rAwake >= startTimestamp && si2.rTryingToSleep >= si2.rAwake) {
                        long startAwake_sec = (int)((si2.rAwake - startTimestamp) / 1000L);
                        long endAwake_sec = (int)((si2.rTryingToSleep - startTimestamp) / 1000L);
                        int start = (int)(startAwake_sec / 300L);
                        if (start < 0) { start = 0; }
                        else if (start >= amendedDisplay.length) { start = amendedDisplay.length - 1; }
                        int end = (int)(endAwake_sec / 300L);
                        if (end < 0) { end = 0; }
                        else if (end >= amendedDisplay.length) { end = amendedDisplay.length - 1; }
                        Log.d(_CTAG+".amend","Span="+si2.seqNo+", Display Awakes: start="+start+", end="+end);
                        for (int i = start; i <= end; i++) {
                            if (amendedDisplay[i] != ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE) {
                                amendedDisplay[i] = ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE;
                                amendmentsMade = true;
                            }
                        }
                    }
                }

                if (amendedBase.length > 0) {
                    if (si2.rAwake >= startTimestamp && si2.rTryingToSleep >= si2.rAwake) {
                        long startAwake_sec = (int)((si2.rAwake - startTimestamp) / 1000L);
                        long endAwake_sec = (int)((si2.rTryingToSleep - startTimestamp) / 1000L);
                        int start = (int)(startAwake_sec / 30L);
                        if (start < 0) { start = 0; }
                        else if (start >= amendedBase.length) { start = amendedBase.length - 1; }
                        int end = (int)(endAwake_sec / 30L);
                        if (end < 0) { end = 0; }
                        else if (end >= amendedBase.length) { end = amendedBase.length - 1; }
                        Log.d(_CTAG+".amend","Span="+si2.seqNo+", Base Awakes: start="+start+", end="+end);
                        for (int i = start; i <= end; i++) {
                            if (amendedBase[i] != ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE) {
                                amendedBase[i] = ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE;
                                amendmentsMade = true;
                            }
                        }
                    }
                }
            }

            // now potentially amend both hypnograms by inserting "Still Awakes" and following them backwards
            // a back trace ends at any of:  trying to sleep, woke-up, did something, retrying to sleep, still awake, or a deep or light/deep sleep segment
            for (CompanionSleepEpisodeEventsParsedRec eRec: extendedEvents) {
                if (eRec.rEventNo == CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_EPISODE_EVENT_STILL_AWAKE) {
                    if (amendedDisplay.length > 0) {
                        long stillAwake_sec = (int)((eRec.rTimestamp - startTimestamp) / 1000L);
                        boolean didOne = false;
                        int end = (int) (stillAwake_sec / 300L);
                        if (end < 0) { end = 0; }
                        else if (end >= amendedDisplay.length) { end = amendedDisplay.length - 1; }
                        loop1: while (end >= 0) {
                            switch (amendedDisplay[end]) {
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_UNDEFINED:
                                    if (didOne) break loop1;

                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                                    amendedDisplay[end] = ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE;
                                    amendmentsMade = true;
                                    didOne = true;
                                    break;
                            }
                            end--;
                        }
                    }

                    if (amendedBase.length > 0) {
                        long stillAwake_sec = (int)((eRec.rTimestamp - startTimestamp) / 1000L);
                        boolean didOne = false;
                        int end = (int)(stillAwake_sec / 30L);
                        if (end < 0) { end = 0; }
                        else if (end >= amendedBase.length) { end = amendedBase.length - 1; }
                        loop2: while (end >= 0) {
                            switch (amendedBase[end]) {
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_UNDEFINED:
                                    if (didOne) break loop2;

                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                                case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                                    amendedBase[end] = ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE;
                                    amendmentsMade = true;
                                    didOne = true;
                                    break;
                            }
                            end--;
                        }
                    }
                }
            }

            // preserve the amended hypnograms
            iRec.theCSErecord.rAmend_Display_Hypnogram = amendedDisplay;
            iRec.theCSErecord.rAmend_Base_Hypnogram = amendedBase;

            // step through the amended base hypnogram and add up all the component sleep stages
            boolean doingTimeToZ = true;
            iRec.theCSErecord.rAmend_Time_to_Z_min = 0.0;
            iRec.theCSErecord.rAmend_Time_Awake_min = 0.0;
            iRec.theCSErecord.rAmend_Time_REM_min = 0.0;
            iRec.theCSErecord.rAmend_Time_Light_min = 0.0;
            iRec.theCSErecord.rAmend_Time_Deep_min = 0.0;
            iRec.theCSErecord.rAmend_LightChangedToDeep_min = 0.0;
            double timeUnknown_min = 0.0;
            for (int i = inx_baseStartOfSleep; i < iRec.theCSErecord.rAmend_Base_Hypnogram.length; i++) {
                switch (amendedBase[i]) {
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                        if (doingTimeToZ) { iRec.theCSErecord.rAmend_Time_to_Z_min = iRec.theCSErecord.rAmend_Time_to_Z_min + .5; }
                        else { iRec.theCSErecord.rAmend_Time_Awake_min = iRec.theCSErecord.rAmend_Time_Awake_min + .5; }
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                        iRec.theCSErecord.rAmend_Time_REM_min = iRec.theCSErecord.rAmend_Time_REM_min + .5;
                        doingTimeToZ = false;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                        iRec.theCSErecord.rAmend_Time_Light_min = iRec.theCSErecord.rAmend_Time_Light_min + .5;
                        doingTimeToZ = false;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                        iRec.theCSErecord.rAmend_Time_Deep_min = iRec.theCSErecord.rAmend_Time_Deep_min + .5;
                        doingTimeToZ = false;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                        iRec.theCSErecord.rAmend_LightChangedToDeep_min = iRec.theCSErecord.rAmend_LightChangedToDeep_min + .5;
                        iRec.theCSErecord.rAmend_Time_Deep_min = iRec.theCSErecord.rAmend_Time_Deep_min + .5;
                        doingTimeToZ = false;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_UNDEFINED:
                        timeUnknown_min = timeUnknown_min + .5;
                        break;
                }
            }
            iRec.theCSErecord.rAmend_DeepSum = iRec.theZAH_SleepRecord.rDeepSum;    // do not know how to re-compute this

            // step through the amended display hypnogram and look for separated awakenings;
            // the Zeo Headband uses this same coarse grained technique
            iRec.theCSErecord.rAmend_CountAwakenings = 0;
            boolean inAwakeSequence = true;
            for (int i = 0; i < iRec.theCSErecord.rAmend_Display_Hypnogram.length; i++) {
                switch (amendedDisplay[i]) {
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_UNDEFINED:
                        // ignored
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_WAKE:
                        if (!inAwakeSequence) {
                            Log.d(_CTAG+".amend","Counted awakening at bar="+i);
                            iRec.theCSErecord.rAmend_CountAwakenings++;
                        }
                        inAwakeSequence = true;
                        break;
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_REM:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_DEEP:
                    case ZAH_SleepRecord.ZAH_HYPNOGRAM_LIGHT_TO_DEEP:
                        inAwakeSequence = false;
                        break;
                }
            }
            if (inAwakeSequence && iRec.theCSErecord.rAmend_CountAwakenings > 0) { iRec.theCSErecord.rAmend_CountAwakenings--; }

            // calculate possible major amendments of time to Z, total sleep time, and count of awakenings
            // did the sleep journal change anything?
            double totalZ_min = iRec.theCSErecord.rAmend_Time_REM_min + iRec.theCSErecord.rAmend_Time_Light_min + iRec.theCSErecord.rAmend_Time_Deep_min;
            if (iRec.theCSErecord.rAmend_CountAwakenings != iRec.theZAH_SleepRecord.rCountAwakenings) { amendmentsMade = true; }
            iRec.theCSErecord.rAmend_Time_Total_Z_min = iRec.theZAH_SleepRecord.rTime_Total_Z_min;
            if (totalZ_min != iRec.theZAH_SleepRecord.rTime_Total_Z_min) {
                iRec.theCSErecord.rAmend_Time_Total_Z_min = totalZ_min;
                amendmentsMade = true;
            }

            // calculate the new ZQ score (see https://www.bulletproofexec.com/zeo-hack/)
            int newZQ = (int)((
                    (iRec.theCSErecord.rAmend_Time_Total_Z_min / 60.0) +
                    (iRec.theCSErecord.rAmend_Time_REM_min / 60.0 / 2.0) +
                    (iRec.theCSErecord.rAmend_Time_Deep_min / 60.0 * 1.5) -
                    (iRec.theCSErecord.rAmend_Time_Awake_min / 60.0 / 2.0) -
                    ((double)iRec.theCSErecord.rAmend_CountAwakenings / 15.0))
                    * 8.5);
            iRec.theCSErecord.rAmend_ZQ_Score = iRec.theZAH_SleepRecord.rZQ_Score;
            if (newZQ != iRec.theZAH_SleepRecord.rZQ_Score) {
                iRec.theCSErecord.rAmend_ZQ_Score = newZQ;
                amendmentsMade = true;
            }

            // cleanup
            if (amendmentsMade) {
                // include an amendment version code in case we need to recompute all amendments in later versions of the App
                iRec.theCSErecord.rAmendedFlags = (iRec.theCSErecord.rAmendedFlags | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_AMENDED | CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_AMENDED_V1);
                iRec.theCSErecord.rAmend_StartOfNight = iRec.theZAH_SleepRecord.rStartOfNight;
                iRec.theCSErecord.rAmend_EndOfNight = iRec.theZAH_SleepRecord.rEndOfNight;
                iRec.theCSErecord.rAmend_Display_Hypnogram_Starttime = iRec.theZAH_SleepRecord.rDisplayHypnogramStartTime;
            }
            if ((iRec.theCSErecord.rAmendedFlags & CompanionDatabaseContract.CompanionSleepEpisodes.SLEEP_AMENDED_FLAGS_AMENDED) == 0) {
                iRec.theCSErecord.rAmend_Display_Hypnogram = null;
                iRec.theCSErecord.rAmend_Base_Hypnogram = null;
            }
            journalSleepIntervals.clear();
        } catch (Exception e) {
            ZeoCompanionApplication.postToErrorLog(_CTAG+".amendTheSleepRecord", e, "CSE ID="+iRec.theCSErecord.rID+"& ZSE ID="+iRec.theZAH_SleepRecord.rSleepEpisodeID);
        }
        return (amendmentsMade | wasNotChecked);
    }
}
