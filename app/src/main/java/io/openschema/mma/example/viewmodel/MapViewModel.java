/*
 * Copyright (c) 2020, The Magma Authors
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openschema.mma.example.viewmodel;

import android.app.Application;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import io.openschema.mma.data.MetricsRepository;
import io.openschema.mma.data.entity.NetworkConnectionsEntity;
import io.openschema.mma.example.fragment.MapFragment;

public class MapViewModel extends AndroidViewModel {

    private static final String TAG = "MapViewModel";
    private final MetricsRepository mMetricsRepository;

    private final MutableLiveData<MapFragment.ClusterData> mCurrentClusterData = new MutableLiveData<>(null);

    public MapViewModel(@NonNull Application application) {
        super(application);
        mMetricsRepository = MetricsRepository.getRepository(application.getApplicationContext());
    }

    public void setSelectedClusterData(MapFragment.ClusterData newData) { mCurrentClusterData.setValue(newData);}
    public MapFragment.ClusterData getSelectedClusterData() { return mCurrentClusterData.getValue();}

    public LiveData<List<NetworkConnectionsEntity>> getAllNetworkConnections() {
        return mMetricsRepository.getAllNetworkConnections();
    }

    public void flagNetworkConnectionReported(NetworkConnectionsEntity entity) {
        mMetricsRepository.flagNetworkConnectionReported(entity);
    }
}
