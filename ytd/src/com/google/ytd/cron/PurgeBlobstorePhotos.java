package com.google.ytd.cron;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.ytd.model.PhotoEntry;
import com.google.ytd.util.PmfUtil;

import java.util.Calendar;
import java.util.List;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class PurgeBlobstorePhotos extends HttpServlet {
  private static final Logger LOG = Logger.getLogger(PurgeBlobstorePhotos.class.getName());
  
  // Any PhotoEntry still in the Blobstore older than this number of hours will be purged.
  private static final int MAX_AGE_IN_HOURS = 6;

  @Inject
  private PmfUtil pmfUtil;

  @SuppressWarnings("unchecked")
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) {
    LOG.info("Starting up...");

    PersistenceManager pm = pmfUtil.getPmf().getPersistenceManager();
    Query query = pm.newQuery(PhotoEntry.class);
    query.setFilter("blobKey != null");
    query.setFilter("created < oldestAllowedDate");
    query.declareParameters("java.util.Date oldestAllowedDate");
    
    Calendar calendar = Calendar.getInstance();
    // The delta needs to be negative, so use 0 - MAX_AGE_IN_HOURS
    calendar.add(Calendar.HOUR_OF_DAY, 0 - MAX_AGE_IN_HOURS);
    LOG.info(calendar.getTime().toString());

    try {
      List<PhotoEntry> photoEntries = (List<PhotoEntry>) query.execute(calendar.getTime());
      LOG.info(String.format("Found %d PhotoEntry(s) still in the Blobstore", photoEntries.size()));
      for (PhotoEntry photoEntry : photoEntries) {
        LOG.info(photoEntry.getId());
      }
    } finally {
      query.closeAll();
    }
  }
}
