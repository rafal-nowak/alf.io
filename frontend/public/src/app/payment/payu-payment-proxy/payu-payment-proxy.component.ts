import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {PaymentProxy, type PaymentMethodId} from '../../model/event';
import {ReservationInfo} from '../../model/reservation-info';
import {PaymentProvider} from '../payment-provider';
import {PayUPaymentProvider} from './payu-payment-provider';

@Component({
  selector: 'app-payu-payment-proxy',
  templateUrl: './payu-payment-proxy.component.html'
})
export class PayUPaymentProxyComponent implements OnChanges {
  @Input()
  method: PaymentMethodId;

  @Input()
  proxy: PaymentProxy;

  @Input()
  reservation: ReservationInfo;

  @Output()
  paymentProvider: EventEmitter<PaymentProvider> = new EventEmitter<PaymentProvider>();

  ngOnChanges(changes: SimpleChanges): void {
    if (this.matchProxyAndMethod && changes.method) {
      this.paymentProvider.emit(new PayUPaymentProvider());
    }
  }

  public get matchProxyAndMethod(): boolean {
    return this.proxy === 'PAYU';
  }

}
