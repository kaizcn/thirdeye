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
package ai.startree.thirdeye.spi.detection;

import ai.startree.thirdeye.spi.datalayer.dto.EnumerationItemDTO;
import java.util.List;
import java.util.Map;

public interface Enumerator {

  List<EnumerationItemDTO> enumerate(Context context);

  class Context {
    private Map<String, Object> params;

    public Map<String, Object> getParams() {
      return params;
    }

    public Context setParams(final Map<String, Object> params) {
      this.params = params;
      return this;
    }
  }
}
