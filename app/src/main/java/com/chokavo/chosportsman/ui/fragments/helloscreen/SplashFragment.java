package com.chokavo.chosportsman.ui.fragments.helloscreen;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import com.chokavo.chosportsman.AppUtils;
import com.chokavo.chosportsman.R;
import com.chokavo.chosportsman.models.DataManager;
import com.chokavo.chosportsman.network.RFManager;
import com.chokavo.chosportsman.network.vk.VKHelper;
import com.chokavo.chosportsman.ormlite.DBHelperFactory;
import com.chokavo.chosportsman.ormlite.dao.SSportTypeDao;
import com.chokavo.chosportsman.ormlite.dao.SportsmanDao;
import com.chokavo.chosportsman.ormlite.dao.SportsmanFavSportTypeDao;
import com.chokavo.chosportsman.ormlite.models.SSportType;
import com.chokavo.chosportsman.ormlite.models.Sportsman;
import com.chokavo.chosportsman.ui.activities.BaseActivity;
import com.chokavo.chosportsman.ui.activities.ChooseSportsActivity;
import com.chokavo.chosportsman.ui.activities.LockerRoomActivity;
import com.chokavo.chosportsman.ui.fragments.BaseFragment;
import com.chokavo.chosportsman.ui.views.ImageSnackbar;
import com.vk.sdk.VKSdk;
import com.vk.sdk.api.VKRequest;
import com.vk.sdk.api.VKResponse;
import com.vk.sdk.api.model.VKApiUserFull;
import com.vk.sdk.api.model.VKList;

import java.sql.SQLException;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by ilyapyavkin on 01.04.16.
 */
public class SplashFragment extends BaseFragment {

    private static final int WAIT_MS = 1400;
    ProgressBar mProgressSplash;
    private View rootView;

    private boolean
            waitDone = false,
            justWait = false;
    private boolean
            favSportTypesLoaded = false,
            vkProfileLoaded = false,
            sportTypesLoaded = false;

    @Override
    public String getFragmentTitle() {
        return null;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_splash, container, false);
        initViews(rootView);

        // 1 загружаем все виды спорта
        loadSportTypes();
        if (VKSdk.isLoggedIn()) {
            // залогинены, подгружаем все необходимые данные
            // TODO: если устройство онлайн - то с сервера, иначе из SQLite
            // 0 загружаем информацию из ВКонтакте
            loadVKProfile();
            // тестируем sqlite
            try {
                SportsmanDao sportsmanDao = DBHelperFactory.getHelper().getSportsmanDao();
                SSportTypeDao sportTypeDao = DBHelperFactory.getHelper().getSportTypeDao();
                SportsmanFavSportTypeDao sportsmanFavSportTypeDao = DBHelperFactory.getHelper().getSportsmanFavSportTypeDao();
                // берем id текущего юзера в sqlite таблице
                int userIdSQLite = DataManager.getInstance().userIdOTMLite;
                if (userIdSQLite > 0) {
                    // извлекаем текущего юзера из SQLite и сохраняем в DataManager
                    Sportsman currentSportsman = sportsmanDao.queryForId(userIdSQLite);
                    DataManager.getInstance().mSportsman = currentSportsman;
                    // из SQLite берем любимые виды спорта юзера
                    loadFavSportTypes(currentSportsman);
                } else {
                    // в SharedPrefs не сохранено id текущего юзера
                    // TODO либо взять с сервера, либо query по каким либо данным
                }
            } catch (SQLException e) {
                ImageSnackbar.make(rootView, ImageSnackbar.TYPE_ERROR, "SQLite error: "+e, Snackbar.LENGTH_LONG).show();
                e.printStackTrace();
            }
        } else {
            // просто ждем
            justWait = true;
        }

        (new WaitTask()).execute();

        return rootView;
    }

    private void loadVKProfile() {
        VKHelper.loadVKUser(new VKRequest.VKRequestListener() {
            @Override
            public void onComplete(VKResponse response) {
                VKList<VKApiUserFull> vkUsers = ((VKList) response.parsedModel);
                VKApiUserFull vkUser = vkUsers.get(0);
                DataManager.getInstance().vkUser = vkUser;
                vkProfileLoaded = true;
                tryMovingOut();
                super.onComplete(response);
            }
        });
    }

    private void loadFavSportTypes(final Sportsman sportsman) {
        try {
            final SportsmanFavSportTypeDao dao = DBHelperFactory.getHelper().getSportsmanFavSportTypeDao();
            final List<SSportType> favSportTypes = dao.getFavSportTypesForSportsman(sportsman);
            if (favSportTypes == null || favSportTypes.size() == 0) {
                // в базе данных видов спорта нет, добавим их через retrofit
                RFManager.getUserSportTypes(sportsman.getServerId(),
                        new Callback<List<SSportType>>() {
                    @Override
                    public void onResponse(Call<List<SSportType>> call, Response<List<SSportType>> response) {
                        List<SSportType> favSportTypes = response.body();
                        List<SSportType> allSportTypes = DataManager.getInstance().getSportTypes();
                        DataManager.getInstance().mSportsman.setFavSportTypes(favSportTypes);

                        for (int i=0; i<favSportTypes.size(); i++) {
                            SSportType sportType = favSportTypes.get(i);
                            SSportType normSportType = SSportType.findByName(allSportTypes, sportType.getTitle());
                            favSportTypes.set(i, normSportType);
                        }

                        try {
                            dao.createListIfNotExist(sportsman, favSportTypes);
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                        favSportTypesLoaded = true;
                        tryMovingOut();
//                mProgressSplash.setVisibility(View.GONE);
                    }

                    @Override
                    public void onFailure(Call<List<SSportType>> call, Throwable t) {
                        Log.e("RETRO", "onFailure: "+t.toString());
                        mProgressSplash.setVisibility(View.GONE);
                        ImageSnackbar.make(rootView, ImageSnackbar.TYPE_ERROR, String.format("Возникла ошибка при загрузке избранных видов спорта"), Snackbar.LENGTH_SHORT).show();
                    }
                });
            } else {
                // в базе данных есть виды спорта. Можно выгрузить в DataManager
                DataManager.getInstance().mSportsman.setFavSportTypes(favSportTypes);
                favSportTypesLoaded = true;
                tryMovingOut();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadSportTypes() {
        try {
            final SSportTypeDao stDao = DBHelperFactory.getHelper().getSportTypeDao();
            final List<SSportType> sportTypes = stDao.getAll();
            if (sportTypes == null || sportTypes.size() == 0 || AppUtils.isDeviceOnline()) {
                // в базе данных видов спорта нет, добавим их через retrofit
                RFManager.getSportTypes(new Callback<List<SSportType>>() {
                    @Override
                    public void onResponse(Call<List<SSportType>> call, Response<List<SSportType>> response) {
                        DataManager.getInstance().setSportTypes(response.body());
                        try {
                            stDao.createList(response.body());
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                        sportTypesLoaded = true;
                        tryMovingOut();
//                mProgressSplash.setVisibility(View.GONE);
                    }

                    @Override
                    public void onFailure(Call<List<SSportType>> call, Throwable t) {
                        Log.e("RETRO", "onFailure: "+t.toString());
                        mProgressSplash.setVisibility(View.GONE);
                        ImageSnackbar.make(rootView, ImageSnackbar.TYPE_ERROR, String.format("Возникла ошибка при загрузке видов спорта"), Snackbar.LENGTH_SHORT).show();
                    }
                });
            } else {
                // в базе данных есть виды спорта. Можно выгрузить в DataManager
                DataManager.getInstance().setSportTypes(sportTypes);
                sportTypesLoaded = true;
                tryMovingOut();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    private void initViews(View rootView) {
        mProgressSplash = (ProgressBar) rootView.findViewById(R.id.progress_splash);
    }

    private void tryMovingOut() {
        if ((sportTypesLoaded && favSportTypesLoaded && vkProfileLoaded || justWait) && waitDone) {
            // проверим, есть ли сейчас активный аккаунт
            if (!VKSdk.isLoggedIn()) {
                launchAnimationFragment(new VKAuthFragment(),
                        VKAuthFragment.getFragmentTag(),
                        BaseActivity.ANIM_LEFT2RIGHT);
            } else {
                List<SSportType> favSportTypes = DataManager.getInstance().mSportsman.getFavSportTypes();
                if (favSportTypes == null || favSportTypes.isEmpty()) {
                    // видов спорта нема
                    startActivity(new Intent(getActivity(), ChooseSportsActivity.class));
                } else {
                    startActivity(new Intent(getActivity(), LockerRoomActivity.class));
                }
                getActivity().finish();
            }
        }
    }

    private class WaitTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            try {
                Thread.sleep(WAIT_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            waitDone = true;
            tryMovingOut();
        }
    }

}
