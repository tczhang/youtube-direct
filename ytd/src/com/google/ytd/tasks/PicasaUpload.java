/*
 * Copyright (c) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.ytd.tasks;

import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.gdata.data.media.mediarss.MediaThumbnail;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.ytd.dao.PhotoSubmissionDao;
import com.google.ytd.model.PhotoEntry;
import com.google.ytd.model.PhotoSubmission;
import com.google.ytd.picasa.PicasaApiHelper;
import com.google.ytd.util.EmailUtil;
import com.google.ytd.util.Util;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class PicasaUpload extends HttpServlet {
  private static final Logger LOG = Logger.getLogger(PicasaUpload.class.getName());

  @Inject
  private Util util;
  @Inject
  private PhotoSubmissionDao photoSubmissionDao;
  @Inject
  private PicasaApiHelper picasaApi;
  @Inject
  private EmailUtil emailUtil;

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    LOG.info("Starting up...");

    try {
      if (!picasaApi.isAuthenticated()) {
        throw new IllegalStateException("No Picasa AuthSub token found in the configuration.");
      }

      String photoEntryId = request.getParameter("id");
      if (util.isNullOrEmpty(photoEntryId)) {
        throw new IllegalArgumentException("Required parameter 'id' is null or empty.");
      }

      LOG.info(String.format("Uploading photo id '%s' to Picasa.", photoEntryId));

      PhotoEntry photoEntry = photoSubmissionDao.getPhotoEntry(photoEntryId);

      BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();

      if (photoEntry.getBlobKey() != null) {
        com.google.gdata.data.photos.PhotoEntry picasaPhoto = picasaApi.doResumableUpload(
            photoEntry);

        if (picasaPhoto == null) {
          throw new IllegalStateException("The resumable upload is still in progress.");
        }
        
        photoEntry.setPicasaUrl(picasaPhoto.getEditLink().getHref());

        // Let's use the smallest thumbnail from Picasa.
        String thumbnailUrl = "";
        int minWidth = Integer.MAX_VALUE;
        for (MediaThumbnail thumbnail : picasaPhoto.getMediaGroup().getThumbnails()) {
          int width = thumbnail.getWidth();
          if (width < minWidth) {
            minWidth = width;
            thumbnailUrl = thumbnail.getUrl();
          }
        }
        photoEntry.setThumbnailUrl(thumbnailUrl);

        photoEntry.setImageUrl(picasaPhoto.getMediaGroup().getContents().get(0).getUrl());

        blobstoreService.delete(photoEntry.getBlobKey());
        photoEntry.setBlobKey(null);

        photoSubmissionDao.save(photoEntry);

        PhotoSubmission photoSubmission = photoSubmissionDao.getSubmissionById(
            photoEntry.getSubmissionId());
        emailUtil.sendNewSubmissionEmail(photoEntry, photoSubmission);
      } else {
        LOG.warning(String.format("PhotoEntry id '%s' does not have an associated entry in the "
            + "Blobstore, so there's nothing to do.", photoEntry.getId()));
      }
    } catch (IllegalArgumentException e) {
      // We don't want to send an error response here, since that will result
      // in the TaskQueue retrying and this is not a transient error.
      LOG.log(Level.WARNING, "", e);
    } catch (IllegalStateException e) {
      LOG.info(e.getMessage());
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}