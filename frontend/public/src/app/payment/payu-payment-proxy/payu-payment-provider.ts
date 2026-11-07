import {SimplePaymentProvider} from '../payment-provider';

export class PayUPaymentProvider extends SimplePaymentProvider {
  override get paymentMethodDeferred(): boolean {
    return false;
  }
}
