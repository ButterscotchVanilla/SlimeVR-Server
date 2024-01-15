import { useLocalization } from '@fluent/react';
import { SaveImuCalibrationRequestT, RpcMessage } from 'solarxr-protocol';
import { useWebsocketAPI } from '@/hooks/websocket-api';
import { BigButton } from './commons/BigButton';
import { FileIcon } from './commons/icon/FileIcon';

export function SaveImuCalibrationButton() {
  const { l10n } = useLocalization();
  const { sendRPCPacket } = useWebsocketAPI();

  const saveImuCalibration = () => {
    sendRPCPacket(
      RpcMessage.SaveImuCalibrationRequest,
      new SaveImuCalibrationRequestT()
    );
  };

  return (
    <BigButton
      text={l10n.getString('widget-save_imu_calibration')}
      icon={<FileIcon width={20} />}
      onClick={saveImuCalibration}
    >
      {}
    </BigButton>
  );
}
