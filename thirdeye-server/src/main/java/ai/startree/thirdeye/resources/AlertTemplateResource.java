/*
 * Copyright 2023 StarTree Inc
 *
 * Licensed under the StarTree Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.startree.ai/legal/startree-community-license
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT * WARRANTIES OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing permissions and limitations under
 * the License.
 */
package ai.startree.thirdeye.resources;

import static ai.startree.thirdeye.util.ResourceUtils.respondOk;

import ai.startree.thirdeye.auth.ThirdEyePrincipal;
import ai.startree.thirdeye.service.AlertTemplateService;
import ai.startree.thirdeye.spi.api.AlertTemplateApi;
import ai.startree.thirdeye.spi.datalayer.dto.AlertTemplateDTO;
import com.codahale.metrics.annotation.Timed;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiKeyAuthDefinition;
import io.swagger.annotations.ApiKeyAuthDefinition.ApiKeyLocation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.SecurityDefinition;
import io.swagger.annotations.SwaggerDefinition;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(tags = "Alert Template", authorizations = {@Authorization(value = "oauth")})
@SwaggerDefinition(securityDefinition = @SecurityDefinition(apiKeyAuthDefinitions = @ApiKeyAuthDefinition(name = HttpHeaders.AUTHORIZATION, in = ApiKeyLocation.HEADER, key = "oauth")))
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public class AlertTemplateResource extends CrudResource<AlertTemplateApi, AlertTemplateDTO> {

  private final AlertTemplateService alertTemplateService;

  @Inject
  public AlertTemplateResource(final AlertTemplateService alertTemplateService) {
    super(alertTemplateService);
    this.alertTemplateService = alertTemplateService;
  }

  @POST
  @Path("load-defaults")
  @Timed
  public Response loadRecommendedTemplates(
      @ApiParam(hidden = true) @Auth final ThirdEyePrincipal principal,
      @FormParam("updateExisting") final boolean updateExisting) {

    return respondOk(alertTemplateService.loadRecommendedTemplates(principal, updateExisting));
  }
}
