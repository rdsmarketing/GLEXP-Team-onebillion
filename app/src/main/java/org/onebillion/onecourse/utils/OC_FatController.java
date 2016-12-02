package org.onebillion.onecourse.utils;

import android.app.AlarmManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.util.ArrayMap;
import android.os.Handler;
import android.widget.Toast;

import org.onebillion.onecourse.mainui.MainActivity;
import org.onebillion.onecourse.mainui.OBMainViewController;
import org.onebillion.onecourse.mainui.OBSectionController;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.onebillion.onecourse.mainui.MainActivity.Config;
import static org.onebillion.onecourse.mainui.OBViewController.MainViewController;

/**
 * Created by michal on 08/08/16.
 */


public class OC_FatController extends OBFatController
{

    private long firstUnstartedIndex;
    public int scoreCorrect,scoreWrong;
    public float finalScore;
    public OC_FatReceiver menu;
    public long sessionSegmentLastActive;

    public static final int OFC_SUCCEEDED = 1,
            OFC_FINISHED_LOW_SCORE = 2,
            OFC_TIMED_OUT = 3,
            OFC_SESSION_TIMED_OUT = 4,
            OFC_NEW_SESSION = 5,
            OFC_SESSION_LOCKED = 6;


    private long sessionTimeout;
    private int unitAttemptsCount, disallowStartHour,disallowEndHour;

    private MlUnitInstance currentUnitInstance;
    private OBUser currentUser;
    private int currentSessionId;
    private long currentSessionStartTime, currentSessionEndTime;

    private Handler timeoutHandler;
    private Runnable timeoutRunnable;

    @Override
    public int buttonFlags()
    {
        int result = OBMainViewController.SHOW_TOP_RIGHT_BUTTON | OBMainViewController.SHOW_BOTTOM_LEFT_BUTTON | OBMainViewController.SHOW_BOTTOM_RIGHT_BUTTON;
        if (showBackButton()) result = result | OBMainViewController.SHOW_TOP_LEFT_BUTTON;
        return result;
    }


    private boolean showTestMenu()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_SHOW_TEST_MENU);
        return (value != null && value.equalsIgnoreCase("true"));
    }


    private boolean showBackButton()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_SHOW_BACK_BUTTON);
        return (value != null && value.equalsIgnoreCase("true"));
    }

    public long getCurrentTime()
    {
        return  System.currentTimeMillis()/1000;
    }

    public void loadMasterListIntoDB()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            String token = OBPreferenceManager.getStringPreference(OBPreferenceManager.PREFERENCE_ML_TOKEN, db);
            String mlname = (String) MainActivity.mainActivity.config.get(MainActivity.CONFIG_MASTER_LIST);
            OBXMLManager xmlManager = new OBXMLManager();
//            InputStream is = OBUtils.getInputStreamForPath(String.format("config/%s", mlname));
            InputStream is = OBUtils.getInputStreamForPath(String.format("masterlists/%s/units.xml", mlname));
            List<OBXMLNode> xml = xmlManager.parseFile(is);
            OBXMLNode rootNode = xml.get(0);
            List<OBXMLNode> masterList = new ArrayList<>();
            String masterListToken = rootNode.attributeStringValue("token");
            if(token == null || !token.equals(masterListToken))
            {
                db.beginTransaction();
                try
                {
                    int startAudio = 0;
                    int nextAudio = OB_Maths.randomInt(3,5);
                    db.doDeleteOnTable(DBSQL.TABLE_UNITS, null);
                    int unitid = 0;
                    int nextStar = 0;
                    for (OBXMLNode levelNode : rootNode.childrenOfType("level"))
                    {
                        int level = levelNode.attributeIntValue("id");

                        List<OBXMLNode> nodes = levelNode.childrenOfType("unit");
                        masterList.addAll(nodes);

                        for (int i=0; i<nodes.size(); i++)
                        {
                            OBXMLNode node = nodes.get(i);

                            if(i+2< nodes.size())
                            {
                                OBXMLNode nextNode = nodes.get(i+2);
                                if(nextNode.attributeIntValue("awardStar") > 0)
                                    nextStar = unitid+2;

                            }

                            int currentStartAudio = -1;
                            if((unitid >= nextStar-2) && (unitid <= nextStar+2))
                                currentStartAudio = -2;

                            if(unitid == nextAudio)
                            {
                                if(currentStartAudio == -2)
                                {
                                    nextAudio++;
                                }
                                else
                                {
                                    currentStartAudio = startAudio;
                                    nextAudio += OB_Maths.randomInt(3,5);
                                    startAudio = (startAudio+1)%8;
                                }
                            }

                            MlUnit.insertUnitFromXMLNodeintoDB(db, node, unitid,level,currentStartAudio);
                            unitid++;
                        }
                    }
                    OBPreferenceManager.setPreference(OBPreferenceManager.PREFERENCE_ML_TOKEN,masterListToken, db);
                    db.setTransactionSuccessful();
                }
                finally
                {
                    db.commitTransaction();
                }
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            if(db != null)
                db.close();
        }


    }


    public void loadUserDB(DBSQL db)
    {
        OBUser u = OBUser.lastUserFromDB(db);
        if (u == null)
        {
            u = OBUser.initAndSaveUserInDB(db, "Student");
            currentUser = u;
            currentSessionId = -1;
            prepareNewSessionInDB(db, u.userid);
        } else
        {
            currentUser = u;
            loadLastSessionFromDB(db, u.userid);
            if(currentSessionId == -1)
            {
                prepareNewSessionInDB(db, u.userid);
            }
            else
            {
                loadLastUnitIndexFromDB(db);
            }
        }
    }

    public void loadUser()
    {
        DBSQL db = null;
        try
        {

            db = new DBSQL(true);
            loadUserDB(db);

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            if(db != null)
                db.close();
        }

        checkAndPrepareNewSession();
    }

    public void loadLastUnitIndexFromDB(DBSQL db)
    {
        int lastUnitID = currentUser.lastUnitIDFromDB(db);
        if (!unitCompletedByUser(db,lastUnitID))
            lastUnitID--;

        firstUnstartedIndex = lastUnitID + 1;
    }


    public void deleteDBFile()
    {
        DBSQL.deleteDB();
    }

    @Override
    public void startUp()
    {
        try
        {
            sessionTimeout = MainActivity.mainActivity.configIntForKey(MainActivity.CONFIG_SESSION_TIMEOUT);
            unitAttemptsCount = MainActivity.mainActivity.configIntForKey(MainActivity.CONFIG_UNIT_TIMEOUT_COUNT);
            String disallowHours = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_DISALLOW_HOURS);
            String[] disallowArray = disallowHours.split(",");
            disallowStartHour = Integer.valueOf(disallowArray[0]);
            disallowEndHour = Integer.valueOf(disallowArray[1]);
        } catch (Exception e)
        {
            sessionTimeout =0;
            unitAttemptsCount = 3;
            disallowStartHour = 0;
            disallowEndHour = 0;
        }

        initDB();

        timeoutHandler = new Handler();

        prepareAlarm();

        continueFromLastUnit();

        if (showTestMenu())
        {
            MainViewController().pushViewControllerWithName("OC_TestMenu", false, false, "menu");
        }
        else
        {
            String menuClassName = (String) Config().get(MainActivity.CONFIG_MENU_CLASS);
            String appCode = (String) Config().get(MainActivity.CONFIG_APP_CODE);
            if (menuClassName != null && appCode != null)
            {
                OBBrightnessManager.sharedManager.onContinue();
                MainViewController().pushViewControllerWithNameConfig(menuClassName, appCode, false, false, null);

            }
        }
    }

    public void prepareAlarm()
    {
        if(disallowEndHour == disallowStartHour)
            return;

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(getCurrentTime() * 1000);
        //calendar.add(Calendar.DATE,1);
        calendar.set(Calendar.HOUR_OF_DAY, disallowStartHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        OBAlarmManager.scheduleRepeatingAlarm(calendar.getTimeInMillis(), AlarmManager.INTERVAL_DAY, OBAlarmManager.REQUEST_SESSION_CHECK);

        calendar.set(Calendar.HOUR_OF_DAY, disallowEndHour);
        OBAlarmManager.scheduleRepeatingAlarm(calendar.getTimeInMillis(), AlarmManager.INTERVAL_DAY, OBAlarmManager.REQUEST_SESSION_CHECK2);
    }

    public boolean checkAndPrepareNewSession()
    {
        if(currentSessionLocked())
            return false;

        if(currentSessionStartTime == 0)
            return true;

        Calendar currentCalendar = Calendar.getInstance();
        Calendar calendarLastSession = Calendar.getInstance();

        currentCalendar.setTimeInMillis(getCurrentTime()*1000);
        calendarLastSession.setTimeInMillis(currentSessionStartTime*1000);

        if(currentCalendar.get(Calendar.DAY_OF_YEAR) != calendarLastSession.get(Calendar.DAY_OF_YEAR)
                || currentCalendar.get(Calendar.YEAR) != calendarLastSession.get(Calendar.YEAR))
        {
            prepareNewSession();
            return true;
        }
        return false;
    }

    public boolean currentSessionLocked()
    {
        if(disallowEndHour == disallowStartHour)
            return false;

        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(getCurrentTime()*1000);
        int hourNow = currentCalendar.get(Calendar.HOUR_OF_DAY);

        boolean hourIsBetween = (disallowEndHour > disallowStartHour && hourNow >= disallowStartHour && hourNow < disallowEndHour)
                || (disallowEndHour < disallowStartHour && (hourNow >= disallowStartHour || hourNow < disallowEndHour));
        return hourIsBetween;
    }

    public void initDB()
    {
        loadMasterListIntoDB();
        loadUser();
    }

    public void continueFromLastUnit()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(false);
            loadLastUnitIndexFromDB(db);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }

    @Override
    public void initScores()
    {
        if(currentUnitInstance == null)
            return;
        scoreCorrect = scoreWrong = 0;
    }

    @Override
    public void gotAnswerRight(int amount)
    {
        scoreCorrect += amount;
    }

    @Override
    public void gotAnswerWrong(int amount)
    {
        scoreWrong += amount;
    }

    @Override
    public void completeEvent(OBSectionController cont)
    {
        cancelTimeout();
        updateScores();

        if(currentSessionLocked())
            signalSessionLocked();
        else if (finalScore >= currentUnit().passThreshold)
            signalSectionSucceeded();
        else
            signalSectionFailed();

        currentUnitInstance = null;

        try
        {
            cont.displayAward();
        }catch (Exception e)
        {

        }
        cont.exitEvent();
    }

    public void completeEvent2(OBSectionController cont)
    {
        cancelTimeout();
        updateScores2();

        if(currentSessionLocked())
            signalSessionLocked();
        else if (finalScore >= currentUnit().passThreshold)
            signalSectionSucceeded();
        else
            signalSectionFailed();

        currentUnitInstance = null;

        cont.exitEvent();
    }

    public void triggerTimeout()
    {
        cancelTimeout();
        timeOutUnit(currentUnitInstance);
    }

    public void timeOutUnit(MlUnitInstance unitInstance)
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            if(currentSessionLocked())
            {
                signalSessionLocked();
            }
            else if(unitCompletedByUser(db, unitInstance.mlUnit.unitid))
            {
                signalSectionFailed();
            }
            else
            {
                signalSectionTimedOut();
            }
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }

        currentUnitInstance = null;

        if(unitInstance.sectionController != null && !unitInstance.sectionController._aborting)
            unitInstance.sectionController.exitEvent();
    }

    @Override
    public void updateScores()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            int tot = scoreCorrect + scoreWrong;
            finalScore = 1;
            if (tot > 0)
                finalScore = scoreCorrect * 1.0f / tot;

            currentUnitInstance.endtime = getCurrentTime();
            currentUnitInstance.score = finalScore;
            currentUnitInstance.elapsedtime = (int)(currentUnitInstance.endtime - currentUnitInstance.starttime);
            currentUnitInstance.updateDataInDB(db);
            checkCurrentSessionTimeout(db);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }

    public void updateScores2()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            int tot = scoreCorrect + scoreWrong;
            finalScore = 1;
            if (tot > 0)
                finalScore = scoreCorrect * 1.0f / tot;

            currentUnitInstance.endtime = getCurrentTime();
            currentUnitInstance.score = finalScore;
            currentUnitInstance.elapsedtime = 30;
            currentUnitInstance.updateDataInDB(db);
            checkCurrentSessionTimeout(db);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }

    public Map<String,Object> commandWith(int code, MlUnit unit)
    {
        Map<String,Object> command = new ArrayMap<>();
        command.put("code", code);
        command.put("unit", unit);
        return command;
    }

    public void singalNewSession()
    {
        menu.receiveCommand(commandWith(OFC_NEW_SESSION,currentUnit()));
    }

    public void signalSectionFailed()
    {
        menu.receiveCommand(commandWith(OFC_FINISHED_LOW_SCORE,currentUnit()));
    }

    public void signalSectionSucceeded()
    {
        menu.receiveCommand(commandWith(OFC_SUCCEEDED,currentUnit()));
    }

    public void signalSectionTimedOut()
    {
        menu.receiveCommand(commandWith(OFC_TIMED_OUT,currentUnit()));
    }

    public void signalSessionTimedOut()
    {
        menu.receiveCommand(commandWith(OFC_SESSION_TIMED_OUT,currentUnit()));
    }

    public void signalSessionLocked()
    {
        menu.receiveCommand(commandWith(OFC_SESSION_LOCKED,currentUnit()));
    }


    public List<MlUnit> requestNextUnits(int count)
    {
        List<MlUnit> arr = new ArrayList<>();
        for (int i = 0;i < count;i++)
        {
            arr.add(MlUnit.mlUnitforUnitID(currentUnit().unitid + 1 + i));
        }
        return arr;
    }

    public MlUnit requestNextUnit()
    {
        return MlUnit.mlUnitforUnitID(firstUnstartedIndex);
    }

    private MlUnit currentUnit()
    {
        if(currentUnitInstance == null)
            return null;
        else
            return currentUnitInstance.mlUnit;
    }

    public long getLastUnitId()
    {
        return firstUnstartedIndex -1;
    }

    public void setCurrentUnitId(long unitid)
    {
        firstUnstartedIndex = unitid;
    }

    public Map<String,Object> getLastCommand()
    {
        MlUnit unit = null;
        int code = -1;
        DBSQL db = new DBSQL(false);
        try
        {
            unit = MlUnit.mlUnitforUnitIDFromDB(db,getLastUnitId());

            if(currentSessionLocked())
                code = OFC_SESSION_LOCKED;
            else if(currentSessionFinished())
                code = OFC_SESSION_TIMED_OUT;
            else if(currentSessionReadyToStart() || unit == null)
                code =  OFC_NEW_SESSION;
            else
                code = OFC_SUCCEEDED;
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }

        return commandWith(code,unit);
    }

    public void sectionStartedWithUnit(MlUnit unit)
    {
        if(unit.unitid >= firstUnstartedIndex)
        {
            firstUnstartedIndex = unit.unitid+1;
        }
        currentUnitInstance = MlUnitInstance.initWithMlUnit(unit,currentUser.userid,currentSessionId,getCurrentTime());
        initScores();
    }

    public void startSectionByUnit(final MlUnit unit)
    {
        sectionStartedWithUnit(unit);
        final String lastAppCode = (String)MainActivity.mainActivity.config.get(MainActivity.CONFIG_APP_CODE);

        new OBRunnableSyncUI()
        {
            @Override
            public void ex ()
            {
                try
                {
                    MainActivity.mainActivity.updateConfigPaths(unit.config, false, unit.lang);
//                    if(OBMainViewController.MainViewController().pushViewControllerWithNameConfig("OC_TestEvent","oc-childmenu",true,true,"test"))
                    if(MainViewController().pushViewControllerWithNameConfig(unit.target,unit.config,true,true,unit.params))
                    {
                        currentUnitInstance.sectionController = MainViewController().topController();
                        startUnitInstanceTimeout(currentUnitInstance);
                    }
                    else
                    {
                        if (MainActivity.mainActivity.isDebugMode())
                        {
                            Toast.makeText(MainActivity.mainActivity, unit.target + " hasn't been converted to Android yet.", Toast.LENGTH_LONG).show();
                            MainActivity.mainActivity.updateConfigPaths(lastAppCode, false, null);
                        }
                    }
                }
                catch (Exception exception)
                {
                    Logger logger = Logger.getAnonymousLogger();
                    logger.log(Level.SEVERE, "Error in runOnMainThread", exception);

                    MainActivity.mainActivity.updateConfigPaths(lastAppCode, false, null);
                }
            }
        }.run();

    }

    public void userActivityOccurred(long systime)
    {
        sessionSegmentLastActive = systime;
    }

    public long lastPlayedUnitIndexFromDB(DBSQL db)
    {
        return MlUnitInstance.lastPlayedUnitIndexForUserIDInDB(db, currentUser.userid);
    }

    public void resetDatabase()
    {
        DBSQL.deleteDB();
        initDB();

    }

    public void refreshUnitsList()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            db.doDeleteOnTable(DBSQL.TABLE_UNITS,null);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
        loadMasterListIntoDB();
    }

    public void checkCurrentSessionTimeout(DBSQL db)
    {
        if(sessionTimeout <= 0)
            return;

        Map<String,String> whereMap  = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(currentUser.userid));
        whereMap.put("sessionid",String.valueOf(currentSessionId));
        Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_UNIT_INSTANCES, Collections.singletonList("SUM(elapsedtime) as elapsedtime"), whereMap);
        long elapsedTime = 0;
        if(cursor.moveToFirst())
            elapsedTime = cursor.getLong(cursor.getColumnIndex("elapsedtime"));

        cursor.close();

        if(elapsedTime >= sessionTimeout)
            finishCurrentSessionInDB(db);
    }

    public int unitAttemtpsCountInDB(DBSQL db, int unitid)
    {
        Map<String,String> whereMap  = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(currentUser.userid));
        whereMap.put("unitid",String.valueOf(unitid));
        Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_UNIT_INSTANCES, Collections.singletonList("COUNT(*) as count"), whereMap);
        int count = 0;
        if(cursor.moveToFirst())
            count = cursor.getInt(cursor.getColumnIndex("count"));

        cursor.close();

        return count;
    }

    public boolean unitCompletedByUser(DBSQL db, int unitid)
    {
        Cursor cursor = db.prepareRawQuery(String.format("SELECT unitid FROM %s WHERE userid = ? AND unitid = ? AND endtime > 0", DBSQL.TABLE_UNIT_INSTANCES),
                Arrays.asList(String.valueOf(currentUser.userid),String.valueOf(unitid)));

        boolean rowExists = cursor.moveToFirst();
        cursor.close();
        if(rowExists)
            return true;


        if(unitAttemptsCount>0 && unitAttemptsCount <= unitAttemtpsCountInDB(db, unitid))
            return true;

        return false;
    }

    public boolean currentSessionFinished()
    {
        if(currentSessionId < 0)
            return true;

        return currentSessionEndTime > 0;
    }


    private void loadLastSessionFromDB(DBSQL db, int userid)
    {
        Map<String,String> whereMap  = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(userid));
        currentSessionId = -1;
        currentSessionEndTime = currentSessionStartTime = 0;
        try
        {
            //Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_SESSIONS, Arrays.asList("sessionid", "starttime", "endtime"), whereMap);
            Cursor cursor = db.prepareRawQuery("SELECT SE.sessionid as sessionid, starttime, endtime FROM "+DBSQL.TABLE_SESSIONS+" AS SE " +
                    "JOIN (SELECT userid, MAX(sessionid) AS sessionid FROM "+DBSQL.TABLE_SESSIONS+" WHERE userid = ? GROUP BY userid ) " +
                    "TAB ON TAB.userid = SE.userid AND TAB.sessionid = SE.sessionid"
                    ,Collections.singletonList(String.valueOf(userid)));
            if (cursor.moveToFirst())
            {
                currentSessionStartTime = cursor.getLong(cursor.getColumnIndex("starttime"));
                currentSessionEndTime = cursor.getLong(cursor.getColumnIndex("endtime"));
                currentSessionId = cursor.getInt(cursor.getColumnIndex("sessionid"));
            }
            cursor.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public int currentSessionUnitCount()
    {
        if(currentSessionId < 0)
            return 0;

        int result = 0;

        DBSQL db = null;
        try
        {
            db = new DBSQL(false);
            result =  sessionUnitCountFromDB(db, currentSessionId, currentUser.userid);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
        return result;
    }

    private int sessionUnitCountFromDB(DBSQL db, int sessionid, int userid)
    {
        if(sessionid < 0)
            return 0;

        int result = 0;

        Map<String,String> whereMap  = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(userid));
        whereMap.put("sessionid",String.valueOf(sessionid));

        Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_UNIT_INSTANCES,Collections.singletonList("COUNT(DISTINCT(unitid)) as count"),whereMap);

        if(cursor.moveToFirst())
            result = cursor.getInt(cursor.getColumnIndex("count"));

        cursor.close();
        return result;
    }

    public void prepareNewSession()
    {
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            prepareNewSessionInDB(db, currentUser.userid);
            loadLastUnitIndexFromDB(db);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }


    public void prepareNewSessionInDB(DBSQL db, int userid)
    {
        if(!currentSessionFinished())
            finishCurrentSessionInDB(db);

        fixMissingStarsInDB(db);

        currentSessionStartTime = 0;
        currentSessionEndTime = 0;

        int sessionid = currentSessionId;
        if(sessionid<0)
            sessionid = 1;
        else
            sessionid++;

        ContentValues contentValues = new ContentValues();
        contentValues.put("userid", userid);
        contentValues.put("sessionid", sessionid);
        contentValues.put("starttime",currentSessionStartTime);
        db.doInsertOnTable(DBSQL.TABLE_SESSIONS,contentValues);
        currentSessionId = sessionid;

        loadLastUnitIndexFromDB(db);
    }

    public void fixMissingStarsInDB(DBSQL db)
    {
        Cursor cursor = db.prepareRawQuery(String.format("SELECT TAB.level as level, TAB.awardStar as awardStar " +
                "FROM (SELECT DISTINCT(UI.unitid), userid, level, awardStar " +
                "FROM "+DBSQL.TABLE_UNITS+" AS U JOIN "+DBSQL.TABLE_UNIT_INSTANCES+" AS UI ON UI.unitid = U.unitid  " +
                "WHERE (endtime>0 OR (SELECT COUNT(*) FROM "+DBSQL.TABLE_UNIT_INSTANCES+" WHERE unitid = UI.unitid AND userid = UI.userid) >= %d) AND userid = %d AND awardStar >0) TAB " +
                "LEFT JOIN "+DBSQL.TABLE_STARS+" AS S ON  TAB.userid = S.userid AND TAB.level = S.level AND TAB.awardStar = S.starnum WHERE S.userid IS NULL",
                unitAttemptsCount , currentUser.userid)
                ,null);
        Map<Integer,List<Integer>> result = new ArrayMap<>();
        if(cursor.moveToFirst())
        {
            while (cursor.isAfterLast() == false)
            {
                int level = cursor.getInt(cursor.getColumnIndex("level"));
                int awardStar = cursor.getInt(cursor.getColumnIndex("awardStar"));
                if(result.get(level) == null)
                    result.put(level, new ArrayList<Integer>());
                result.get(level).add(awardStar);
                cursor.moveToNext();
            }

        }
        cursor.close();

        for(int level : result.keySet())
        {
            String colour = lastStarColourForLevelFromDB(db, currentUser.userid, level);
            if (colour == null)
                colour = String.valueOf(OB_Maths.randomInt(1, 5));
            for(int awardNum : result.get(level))
                saveStarInDB(db,currentUser.userid,level,awardNum,colour);
        }
    }

    public boolean currentSessionReadyToStart()
    {
        if(currentSessionLocked())
            return false;

        return currentSessionStartTime == 0;
    }

    public void startCurrentSession()
    {
        if(!currentSessionReadyToStart())
            return;

        currentSessionStartTime = getCurrentTime();
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            updateCurrentSessionTimeInDB(db,"starttime", currentSessionStartTime);
        }
        catch (Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }

    }

    public void finishCurrentSessionInDB(DBSQL db)
    {
        if(sessionTimeout <= 0)
            return;

        if(currentSessionId < 0)
            return;

        currentSessionEndTime =  getCurrentTime();

        updateCurrentSessionTimeInDB(db,"endtime", currentSessionEndTime);
    }

    private boolean updateCurrentSessionTimeInDB(DBSQL db, String fieldName, long value)
    {
        Map<String,String> whereMap  = new ArrayMap<>();

        whereMap.put("userid",String.valueOf(currentUser.userid));
        whereMap.put("sessionid",String.valueOf(currentSessionId));
        ContentValues contentValues = new ContentValues();
        contentValues.put(fieldName,value);
        return  db.doUpdateOnTable(DBSQL.TABLE_SESSIONS,whereMap, contentValues) > 0;
    }

    public void saveStarForUnit(MlUnit unit,String colour)
    {
        if(unit == null)
            return;

        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            saveStarInDB(db,currentUser.userid,unit.level,unit.awardStar,colour);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }

    public void saveStarInDB(DBSQL db, int userid, int level, int awardStar, String colour)
    {
        ContentValues contentValues = new ContentValues();
        contentValues.put("userid",userid);
        contentValues.put("level",level);
        contentValues.put("starnum",awardStar);
        contentValues.put("colour",colour);

        boolean result = db.doReplaceOnTable(DBSQL.TABLE_STARS,contentValues) > 0;

    }

    public Map<Integer,String> starsForLevel(int level)
    {
        Map<Integer,String> result = null;
        DBSQL db = null;
        try
        {
            db = new DBSQL(false);
            result = starsForLevelFromDB(db,level);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
        return result;
    }

    private Map<Integer,String> starsForLevelFromDB(DBSQL db, int level)
    {
        Map<String,String> whereMap = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(currentUser.userid));
        whereMap.put("level",String.valueOf(level));
        Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_STARS,Arrays.asList("starnum","colour"),whereMap);

        Map<Integer,String> result = new ArrayMap<>();
        if(cursor.moveToFirst())
        {
            while (cursor.isAfterLast() == false)
            {
                result.put(cursor.getInt(cursor.getColumnIndex("starnum")), cursor.getString(cursor.getColumnIndex("colour")));
                cursor.moveToNext();
            }

        }
        cursor.close();

        return result;
    }

    public String lastStarColourForLevelFromDB(DBSQL db, int userid, int level)
    {
        Map<String,String> whereMap = new ArrayMap<>();
        whereMap.put("userid",String.valueOf(userid));
        whereMap.put("level",String.valueOf(level));
        Cursor cursor = db.prepareRawQuery("SELECT colour FROM "+DBSQL.TABLE_STARS+" WHERE userid = ? ORDER BY level DESC,starnum DESC LIMIT 1",
                Collections.singletonList(String.valueOf(userid)));
        String result = null;
        if(cursor.moveToFirst())
            result = cursor.getString(cursor.getColumnIndex("colour"));

        cursor.close();
        return result;
    }

    public String starForLevel(int level,int starnum)
    {
        String result = null;
        DBSQL db = null;
        try
        {
            db = new DBSQL(false);
            Map<String,String> whereMap = new ArrayMap<>();
            whereMap.put("userid",String.valueOf(currentUser.userid));
            whereMap.put("level",String.valueOf(level));
            whereMap.put("starnum",String.valueOf(starnum));
            Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_STARS, Collections.singletonList("colour"),whereMap);

            if(cursor.moveToFirst())
                result = cursor.getString(cursor.getColumnIndex("colour"));

            cursor.close();
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
        return result;
    }

    public boolean shouldAwardStar(MlUnit unit)
    {
        if(unit == null)
            return false;

        if(unit.awardStar < 0)
            return false;

        return starForLevel(unit.level,unit.awardStar) == null;
    }

    @Override
    public void onPause(OBSectionController cont)
    {

    }

    @Override
    public void onResume(OBSectionController cont)
    {
        checkTimeout(currentUnitInstance);
    }

    @Override
    public void onExitSection(OBSectionController cont)
    {
        cancelTimeout();
    }


    public void startUnitInstanceTimeout(final MlUnitInstance unitInstance)
    {
        cancelTimeout();

        timeoutRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                if(checkTimeout(unitInstance))
                    timeoutHandler.postDelayed(this,60*1000);
            }
        };

        timeoutHandler.postDelayed(timeoutRunnable,(int)(unitInstance.mlUnit.targetDuration)*1000); //currentInstance.mlUnit.targetDurationstance.)

    }

    public void cancelTimeout()
    {
        if(timeoutRunnable != null)
            timeoutHandler.removeCallbacks(timeoutRunnable);

        timeoutRunnable = null;
    }

    public boolean checkTimeout(MlUnitInstance unitInstance)
    {
        if (!allowsTimeOut())
            return false;

        if(unitInstance != currentUnitInstance)
            return false;

        if(unitInstance.sectionController == null || unitInstance.sectionController._aborting)
            return false;

        if((unitInstance.starttime + unitInstance.mlUnit.targetDuration) <= getCurrentTime())
        {
            MainActivity.log("Time out!!");
            timeOutUnit(unitInstance);
            return false;
        }
        return true;
    }


    public boolean allowsTimeOut()
    {
        String value = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_ALLOWS_TIMEOUT);
        return (value != null && value.equals("true"));
    }

    public boolean currentPathComplete()
    {
        int maxUnitID = 0;
        boolean unitCompleted = false;
        DBSQL db = null;
        try
        {
            db = new DBSQL(false);
            Map<String,String> whereMap = new ArrayMap<>();
            Cursor cursor = db.doSelectOnTable(DBSQL.TABLE_UNITS, Collections.singletonList("MAX(unitid) as unitid"),null);
            if(cursor.moveToFirst())
                maxUnitID = cursor.getInt(cursor.getColumnIndex("unitid"));
            cursor.close();

            unitCompleted = unitCompletedByUser(db,maxUnitID);

        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
        return unitCompleted;
    }

    public void resetProgress()
    {
        int maxUnitID = 0;
        boolean unitCompleted = false;
        DBSQL db = null;
        try
        {
            db = new DBSQL(true);
            db.doDeleteOnTable(DBSQL.TABLE_STARS,null);
            db.doDeleteOnTable(DBSQL.TABLE_UNIT_INSTANCES,null);
            db.doDeleteOnTable(DBSQL.TABLE_SESSIONS,null);

            firstUnstartedIndex = 0;
            loadUserDB(db);
        }
        catch(Exception e)
        {

        }
        finally
        {
            if(db != null)
                db.close();
        }
    }


    //borrowed from http://www.ben-daglish.net/moon.shtml
    public int getCurrentMoonPhase()
    {
        Calendar calendar = Calendar.getInstance();
        int lp = 2551443; // full moon phase(from full to full) in seconds
        calendar.setTimeInMillis(getCurrentTime()*1000);
        calendar.set(Calendar.HOUR,20);
        calendar.set(Calendar.MINUTE,35);
        calendar.set(Calendar.SECOND,0);
        long now = calendar.getTimeInMillis();

        calendar.set(Calendar.YEAR,1970);
        calendar.set(Calendar.MONTH,Calendar.JANUARY);
        calendar.set(Calendar.DATE,7);
        long new_moon = calendar.getTimeInMillis();
        long phase = ((now - new_moon)/1000) % lp;
        return (int)(Math.floor(phase /(24*3600)) + 1);
    }

}